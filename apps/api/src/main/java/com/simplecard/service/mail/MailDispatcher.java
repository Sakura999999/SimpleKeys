package com.simplecard.service.mail;

import com.simplecard.config.MailChannelProperties;
import com.simplecard.config.MailSenderRouter;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一发信入口 — 全站邮件都从这里出去。
 *
 * <p><b>三件事必须集中在一处：</b>
 * <ol>
 *   <li><b>选通道</b>：按收件域路由，避免国内/海外互相拖累</li>
 *   <li><b>重试与降级</b>：同通道退避重试，通道级故障换通道</li>
 *   <li><b>失败分类</b>：区分「该重试」与「绝不能重试」，后者会累积硬退拖垮发件域</li>
 * </ol>
 *
 * <p><b>为什么降级不会重复投递：</b>只有 {@link MailFailureType#channelSwappable()} 为真才换通道，
 * 而这些分类（认证失败、连接失败、限流）都发生在 SMTP 服务器接收邮件<b>之前</b>，
 * 不存在"已投递又重发"的可能。收件人层面的拒绝不换通道，因为换通道结果一样。
 */
@Slf4j
@Component
public class MailDispatcher {

    private final MailSenderRouter router;
    private final MailChannelProperties properties;
    private final MailDeliveryStats stats;

    public MailDispatcher(MailSenderRouter router, MailChannelProperties properties, MailDeliveryStats stats) {
        this.router = router;
        this.properties = properties;
        this.stats = stats;
    }

    /** 功能是否可用（开关打开且至少一个通道就绪） */
    public boolean isAvailable() {
        return properties.isEnabled() && router.hasUsableChannel();
    }

    /**
     * 发送一封邮件。
     *
     * <p>不抛异常：调用点都在 {@code @Async} 线程里，异常逃逸只会打到默认异常处理器，
     * 既不告警也无法统计。结果通过返回值与 micrometer 表达。
     */
    public MailDeliveryResult send(MailMessage message) {
        if (!properties.isEnabled()) {
            return MailDeliveryResult.skipped("mail disabled");
        }
        if (message == null || message.to() == null || message.to().isBlank()) {
            return MailDeliveryResult.skipped("empty recipient");
        }
        if (!router.hasUsableChannel()) {
            return MailDeliveryResult.skipped("no usable channel");
        }
        if (stats.isSuppressed(message.to())) {
            log.info("Skip mail to hard-bounced address {}", maskEmail(message.to()));
            return MailDeliveryResult.skipped("recipient suppressed after hard bounce");
        }

        MailSenderRouter.Route route = router.route(message.to());
        if (route == null) {
            return MailDeliveryResult.skipped("no route for recipient");
        }

        List<MailSenderRouter.Route> allRoutes = router.routes();
        Set<String> tried = new HashSet<>();
        int totalAttempts = 0;
        MailFailureType lastFailure = null;
        String lastDetail = null;
        String lastChannel = route.name();

        while (route != null) {
            tried.add(route.name());
            lastChannel = route.name();

            MailFailureType failure = null;
            for (int attempt = 1; attempt <= properties.getRetry().getMaxAttempts(); attempt++) {
                totalAttempts++;
                try {
                    doSend(route, message);
                    MailDeliveryResult ok = MailDeliveryResult.ok(route.name(), totalAttempts);
                    stats.record(message.to(), message.purpose(), ok);
                    log.info("Mail sent via {} (attempts={}, to={}, purpose={})",
                            route.name(), totalAttempts, maskEmail(message.to()), message.purpose());
                    return ok;
                } catch (Exception e) {
                    failure = MailFailureClassifier.classify(e);
                    lastFailure = failure;
                    lastDetail = summarize(e);
                    boolean willRetry = failure.retryable()
                            && attempt < properties.getRetry().getMaxAttempts();
                    log.warn("Mail send failed on {} (attempt {}/{}, reason={}): {}",
                            route.name(), attempt, properties.getRetry().getMaxAttempts(), failure, lastDetail);
                    if (!willRetry) {
                        break;
                    }
                    if (!sleepBackoff(attempt)) {
                        break;
                    }
                }
            }

            // 通道级故障：换一个未尝试过的通道再战
            MailSenderRouter.Route next = null;
            if (failure != null && failure.channelSwappable()) {
                next = allRoutes.stream()
                        .filter(r -> !tried.contains(r.name()))
                        .findFirst()
                        .orElse(null);
                if (next != null) {
                    log.warn("Failover mail channel: {} -> {} (reason={})", route.name(), next.name(), failure);
                }
            }
            route = next;
        }

        MailDeliveryResult failed = MailDeliveryResult.fail(lastChannel, lastFailure, totalAttempts, lastDetail);
        stats.record(message.to(), message.purpose(), failed);
        return failed;
    }

    // ── 内部 ──

    private void doSend(MailSenderRouter.Route route, MailMessage message) throws Exception {
        String from = resolveFrom(route, message.purpose());
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("no from address configured for purpose " + message.purpose());
        }

        MimeMessage mime = route.sender().createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.html(), true);
        if (message.replyTo() != null && !message.replyTo().isBlank()) {
            helper.setReplyTo(message.replyTo());
        }
        route.sender().send(mime);
    }

    /**
     * 按用途选发件人。
     *
     * <p>配置了专用地址就用专用地址；没配则回退到通道自身的 from，
     * 保证旧部署（只有一个 MAIL_USERNAME）升级后行为不变。
     */
    private String resolveFrom(MailSenderRouter.Route route, MailPurpose purpose) {
        MailChannelProperties.From from = properties.getFrom();
        String configured = purpose == MailPurpose.VERIFICATION ? from.getVerification() : from.getNotification();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return route.defaultFrom();
    }

    /** 线性退避：backoffMs * attempt，上限 maxBackoffMs。验证码场景总等待必须很短 */
    private boolean sleepBackoff(int attempt) {
        long wait = Math.min(properties.getRetry().getBackoffMs() * attempt,
                properties.getRetry().getMaxBackoffMs());
        if (wait <= 0) {
            return true;
        }
        try {
            Thread.sleep(wait);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String summarize(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        // 异常消息可能带上完整邮箱地址，截断并压缩长度，避免日志里出现明文收件人
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
