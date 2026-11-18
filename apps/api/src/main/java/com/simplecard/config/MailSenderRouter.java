package com.simplecard.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 发信通道路由器 — 全站唯一决定「这封信从哪个通道发出去」的地方。
 *
 * <p><b>路由规则（按优先级）：</b>
 * <ol>
 *   <li>收件域命中某个通道的 {@code domains} 后缀 → 用该通道</li>
 *   <li>未命中 → 用兜底通道（{@code mail.default-channel} 指定，或第一个 domains 为空的通道）</li>
 *   <li>再没有 → 用 {@code spring.mail.*} 兼容通道</li>
 * </ol>
 *
 * <p><b>兜底为什么存在：</b>域名列表永远不可能穷尽（企业自建域、新顶级域、用户自有域名）。
 * 没有兜底通道时，这些收件人会被静默判定为「无法送达」，而实际上用主通道发是能成功的。
 *
 * <p><b>超时是硬要求：</b>{@code JavaMailSenderImpl} 默认没有超时。SMTP 服务端不响应时
 * 连接会无限挂起，而调用点都在 {@code @Async} 线程里 —— 少量卡死就能占满线程池，
 * 连发货通知一起堵住。每个通道都必须显式带上三个超时。
 */
@Slf4j
@Component
public class MailSenderRouter {

    /** spring.mail.* 兼容通道名，用于日志与告警 */
    public static final String LEGACY_CHANNEL = "default";

    private final MailChannelProperties properties;

    /** Spring Boot 自动配置的发信器，仅在未配置 mail.channels 时使用 */
    private final ObjectProvider<JavaMailSender> legacySenderProvider;

    private final String legacyHost;
    private final int legacyPort;
    private final String legacyUsername;
    private final String legacyPassword;

    /** 路由表，按配置顺序。空表示没有任何可用通道 */
    private List<Route> routes = new ArrayList<>();
    /** 兜底路由，可能为 null */
    private Route fallback;
    /** 是否退化为 spring.mail.* 单通道（用于启动日志说明） */
    private boolean legacyMode;

    public MailSenderRouter(MailChannelProperties properties,
                            ObjectProvider<JavaMailSender> legacySenderProvider,
                            @Value("${spring.mail.host:}") String legacyHost,
                            @Value("${spring.mail.port:465}") int legacyPort,
                            @Value("${spring.mail.username:}") String legacyUsername,
                            @Value("${spring.mail.password:}") String legacyPassword) {
        this.properties = properties;
        this.legacySenderProvider = legacySenderProvider;
        this.legacyHost = legacyHost;
        this.legacyPort = legacyPort;
        this.legacyUsername = legacyUsername;
        this.legacyPassword = legacyPassword;
    }

    @jakarta.annotation.PostConstruct
    void build() {
        List<Route> built = new ArrayList<>();
        Route fb = null;

        for (var entry : properties.getChannels().entrySet()) {
            String name = entry.getKey();
            MailChannelProperties.Channel cfg = entry.getValue();
            if (!cfg.isEnabled()) {
                log.info("Mail channel '{}' disabled by config", name);
                continue;
            }
            if (!cfg.looksConfigured()) {
                log.warn("Mail channel '{}' 的 host/password 未填全，已跳过", name);
                continue;
            }
            Route route = new Route(name, buildSender(cfg), List.copyOf(cfg.getDomains()), false,
                    orElse(cfg.getFrom(), properties.getFrom().getNotification()));
            built.add(route);
            if (cfg.getDomains() == null || cfg.getDomains().isEmpty()) {
                if (fb == null) {
                    fb = route;
                }
            }
        }

        // 显式指定兜底通道时以配置为准
        if (properties.getDefaultChannel() != null && !properties.getDefaultChannel().isBlank()) {
            String wanted = properties.getDefaultChannel().trim();
            fb = built.stream().filter(r -> r.name().equals(wanted)).findFirst().orElseGet(() -> {
                log.warn("mail.default-channel='{}' 未匹配到任何可用通道，改用自动推断", wanted);
                return built.stream().filter(r -> r.domains().isEmpty()).findFirst().orElse(null);
            });
        }

        // 没有任何自定义通道 → 退化为 spring.mail.*，保持改造前的部署可用。
        // 必须复用 Spring 自动配置的那个 bean：它是按 spring.mail.* 完整装配的，
        // 包括 ssl/starttls/超时。若这里自己重建，这些设置会被 Channel 的默认值覆盖，
        // 典型症状是「配了 MAIL_SSL=false 却仍去握手 SSL」。
        if (built.isEmpty()) {
            legacyMode = true;
            JavaMailSender legacy = legacySenderProvider.getIfAvailable();
            if (legacy != null && isLegacyUsable()) {
                log.info("Using Spring Boot auto-configured mail sender {}:{}", legacyHost, legacyPort);
                Route route = new Route(LEGACY_CHANNEL, legacy, List.of(), true, legacyUsername);
                built.add(route);
                fb = route;
            } else {
                log.info("No usable mail channel: mail.channels 未配置且 spring.mail.* 不完整，邮件功能保持关闭");
            }
        }

        this.routes = List.copyOf(built);
        this.fallback = fb;

        if (properties.isEnabled() && routes.isEmpty()) {
            log.warn("mail.enabled=true 但没有任何可用发信通道，邮件相关功能（发货通知、邮箱验证码）将保持关闭；"
                    + "请配置 mail.channels.* 或 MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD");
        }

        log.info("Mail routes ready: mode={}, channels={}, fallback={}",
                legacyMode ? "legacy-spring-mail" : "multi-channel",
                routes.stream().map(Route::name).toList(),
                fb == null ? "<none>" : fb.name());
    }

    /** 按收件人选通道。没有可用通道时返回 null，调用方必须处理 */
    public Route route(String recipientEmail) {
        if (routes.isEmpty()) {
            return null;
        }
        String domain = domainOf(recipientEmail);
        if (domain != null) {
            for (Route r : routes) {
                for (String d : r.domains()) {
                    String suffix = d.trim().toLowerCase(Locale.ROOT);
                    if (suffix.isEmpty()) continue;
                    if (domain.equals(suffix) || domain.endsWith("." + suffix)) {
                        return r;
                    }
                }
            }
        }
        return fallback != null ? fallback : routes.get(0);
    }

    /** 至少有一个通道可用 —— 供功能入口开关判定 */
    public boolean hasUsableChannel() {
        return !routes.isEmpty();
    }

    public List<Route> routes() {
        return routes;
    }

    public boolean isLegacyMode() {
        return legacyMode;
    }

    /** 供启动日志与运维排查，不含口令 */
    public String describe() {
        if (routes.isEmpty()) {
            return "no-channel";
        }
        StringBuilder sb = new StringBuilder();
        for (Route r : routes) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(r.name()).append('(').append(r.domains().isEmpty() ? "*" : String.join(",", r.domains())).append(')');
        }
        return sb.toString();
    }

    // ── 内部 ──

    private boolean isLegacyUsable() {
        return legacyHost != null && !legacyHost.isBlank()
                && !MailAvailability.PLACEHOLDER_HOSTS.contains(legacyHost.trim().toLowerCase(Locale.ROOT))
                && legacyPassword != null
                && !MailAvailability.PLACEHOLDER_PASSWORDS.contains(legacyPassword.trim());
    }

    /**
     * 构建带超时的 sender。
     *
     * <p>超时必须写在 {@code mail.smtp.*} 上：{@code JavaMailSenderImpl} 本身没有超时字段，
     * 完全依赖底层 JavaMail 的 Properties。
     */
    private JavaMailSenderImpl buildSender(MailChannelProperties.Channel cfg) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.getHost());
        sender.setPort(cfg.getPort());
        sender.setDefaultEncoding("UTF-8");
        if (cfg.getUsername() != null && !cfg.getUsername().isBlank()) {
            sender.setUsername(cfg.getUsername());
            sender.setPassword(cfg.getPassword());
        }

        Properties p = new Properties();
        p.put("mail.smtp.auth", String.valueOf(cfg.isAuth()));
        p.put("mail.smtp.connectiontimeout", String.valueOf(cfg.getConnectionTimeoutMs()));
        p.put("mail.smtp.timeout", String.valueOf(cfg.getReadTimeoutMs()));
        p.put("mail.smtp.writetimeout", String.valueOf(cfg.getWriteTimeoutMs()));

        if (cfg.isStarttls()) {
            // starttls 与隐式 ssl 互斥：同时开启会让握手阶段直接失败
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.ssl.enable", "false");
        } else if (cfg.isSsl()) {
            p.put("mail.smtp.ssl.enable", "true");
        }
        if (cfg.isAuth()) {
            p.put("mail.smtp.ssl.trust", cfg.getHost());
        }
        String bounce = properties.getFrom().getBounce();
        if (bounce != null && !bounce.isBlank()) {
            // Return-Path 决定退信回到哪里，与 From 头分离
            p.put("mail.smtp.from", bounce);
        }
        sender.setJavaMailProperties(p);
        return sender;
    }

    private static String orElse(String value, String fallbackValue) {
        return value != null && !value.isBlank() ? value : fallbackValue;
    }

    private static String domainOf(String email) {
        if (email == null) return null;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return null;
        return email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 一条可用的发信路径。
     *
     * @param name        通道名，用于日志与告警
     * @param sender      已带齐超时与 TLS 参数的发信器
     * @param domains     收件域后缀列表，空表示兜底通道
     * @param fallback    是否为兜底通道
     * @param defaultFrom 通道自身发件人，用于未配置专用发件人时回退
     */
    public record Route(
            String name,
            JavaMailSender sender,
            List<String> domains,
            boolean fallback,
            String defaultFrom
    ) {
    }
}
