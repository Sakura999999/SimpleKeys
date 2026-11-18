package com.simplecard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 邮件发送的完整配置：开关、发件人、重试、多通道。
 *
 * <p><b>为什么需要多通道：</b>国内收件域（QQ/163/企业邮箱）与海外收件域（Gmail/Outlook）
 * 对发信 IP 的信誉评判是分开的。用国内服务商发 Gmail 容易进垃圾箱，用海外服务商发 QQ
 * 延迟高且常被判可疑。因此按收件域路由到不同通道，各自维护自己的发信信誉。
 *
 * <p><b>向后兼容：</b>{@code mail.channels} 为空时，退化为仅使用 Spring Boot 自动配置的
 * {@code spring.mail.*} 单通道，行为与改造前一致。已有部署不需要改任何环境变量。
 *
 * <pre>{@code
 * mail:
 *   enabled: true
 *   from:
 *     verification: verify@mail.example.com
 *     notification: noreply@mail.example.com
 *   channels:
 *     domestic:
 *       host: smtp.qcloudmail.com
 *       domains: [qq.com, 163.com]
 *     overseas:
 *       host: email-smtp.us-east-1.amazonaws.com
 *       domains: []          # 空 = 兜底通道
 * }</pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mail")
public class MailChannelProperties {

    /** 总开关。关闭时所有发信动作直接跳过，不产生任何网络调用 */
    private boolean enabled = false;

    /** 站点地址，用于邮件正文中的链接 */
    private String siteUrl = "https://example.com";

    private From from = new From();

    private Retry retry = new Retry();

    /**
     * 兜底通道名。收件域没有命中任何通道的 {@code domains} 时使用。
     * 留空则按「第一个 domains 为空的通道 → 第一个启用通道」的顺序推断。
     */
    private String defaultChannel;

    /** 通道名 → 通道配置。为空时退化为 spring.mail.* 单通道 */
    private Map<String, Channel> channels = new LinkedHashMap<>();

    /**
     * 发件人分离。
     *
     * <p>验证码与发货通知用不同地址，是为了隔离信誉：任一类邮件被大量投诉或判垃圾时，
     * 不会连带影响另一类。两者可留空，留空时回退到通道自身的 {@code from}。
     */
    @Data
    public static class From {
        /** 发货通知发件人 */
        private String notification;
        /** 验证码发件人。建议用 security@ 或 verify@ 前缀，便于用户在收件箱识别 */
        private String verification;
        /** 退信收集地址，写入 Return-Path。留空则不设置 */
        private String bounce;
    }

    /**
     * 重试策略。
     *
     * <p>总耗时上限约 {@code (maxAttempts-1) * maxBackoffMs}。验证码有效期只有几分钟，
     * 重试窗口必须远小于它，否则重试成功时用户已经放弃了。
     */
    @Data
    public static class Retry {
        /** 总尝试次数（含首次）。1 表示不重试 */
        private int maxAttempts = 3;
        /** 首次退避毫秒，之后按 2 倍递增 */
        private long backoffMs = 200;
        /** 单次退避上限，避免指数增长失控 */
        private long maxBackoffMs = 1000;
    }

    /**
     * 单个发信通道。
     *
     * <p>{@code domains} 为收件域后缀列表（如 {@code qq.com} 同时匹配 {@code qq.com}
     * 与 {@code vip.qq.com}）。列表为空的通道作为兜底通道。
     */
    @Data
    public static class Channel {

        private String host;
        private int port = 465;
        private String username;
        private String password;
        /** 该通道的发件人。留空时用 {@code spring.mail.username} */
        private String from;

        /** 隐式 TLS（465 端口）。与 starttls 二选一 */
        private boolean ssl = true;
        /** 明文连接后升级 TLS（587 端口） */
        private boolean starttls = false;
        /** 是否需要认证。少数内网中继不需要 */
        private boolean auth = true;

        /** 连接超时（毫秒）。必须设置，否则 SMTP 卡住会占死异步线程池 */
        private int connectionTimeoutMs = 5000;
        /** 读超时（毫秒） */
        private int readTimeoutMs = 10000;
        /** 写超时（毫秒） */
        private int writeTimeoutMs = 10000;

        /** 收件域后缀列表。空表示兜底通道 */
        private List<String> domains = new ArrayList<>();

        /** 显式禁用某个通道而不必删除配置 */
        private boolean enabled = true;

        /** 主参数是否填全（host 与 password 非空且不是占位符） */
        public boolean looksConfigured() {
            return host != null && !host.isBlank()
                    && !MailAvailability.PLACEHOLDER_HOSTS.contains(host.trim().toLowerCase())
                    && (password != null && !MailAvailability.PLACEHOLDER_PASSWORDS.contains(password.trim()));
        }
    }
}
