package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.config.MailChannelProperties;
import com.simplecard.config.MailExecutorConfig;
import com.simplecard.service.mail.MailDeliveryStats;
import com.simplecard.service.mail.MailQueueGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 发信运维视图 — 把「失败了、退信了、排队丢了」三件事变成可读的数字。
 *
 * <p><b>为什么必须有这个接口：</b>失败分类和计数器再细，没有出口就等于没有。
 * 项目里只有 {@code SimpleMeterRegistry}（内存版，无 Prometheus 抓取端点），
 * 仅靠日志排查意味着要翻日志才能回答「昨天有多少封没发出去、为什么」。
 * 这里把快照直接暴露成一个 JSON，运维和排障都不必登机器。
 *
 * <p><b>权限：</b>挂在 {@code /admin/**} 下，走全局 ADMIN 角色校验。
 * 返回体里只有计数、通道名与收件域，不含口令，也不含任何收件人地址。
 */
@RestController
@RequestMapping("/admin/mail")
public class AdminMailController {

    private final MailDeliveryStats stats;
    private final MailQueueGuard queueGuard;
    private final MailChannelProperties properties;
    private final ThreadPoolTaskExecutor verificationExecutor;
    private final ThreadPoolTaskExecutor notificationExecutor;

    public AdminMailController(MailDeliveryStats stats,
                               MailQueueGuard queueGuard,
                               MailChannelProperties properties,
                               @Qualifier(MailExecutorConfig.VERIFICATION_EXECUTOR)
                               ThreadPoolTaskExecutor verificationExecutor,
                               @Qualifier(MailExecutorConfig.NOTIFICATION_EXECUTOR)
                               ThreadPoolTaskExecutor notificationExecutor) {
        this.stats = stats;
        this.queueGuard = queueGuard;
        this.properties = properties;
        this.verificationExecutor = verificationExecutor;
        this.notificationExecutor = notificationExecutor;
    }

    /**
     * 发信健康总览。
     *
     * <p>读法：
     * <ul>
     *   <li>{@code delivery.success_rate} 低于 98% → 看 {@code failure_breakdown}</li>
     *   <li>{@code delivery.alert_required} 在涨 → 认证失败或批量硬退，会全线中断</li>
     *   <li>{@code queue.*_rejected} 大于 0 → 线程池打满，验证码该限流、通知该扩容</li>
     * </ul>
     */
    @GetMapping("/delivery-stats")
    public ApiResponse<?> deliveryStats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.isEnabled());
        body.put("from", fromView());
        body.put("channels", channelView());
        body.put("retry", Map.of(
                "max_attempts", properties.getRetry().getMaxAttempts(),
                "backoff_ms", properties.getRetry().getBackoffMs(),
                "max_backoff_ms", properties.getRetry().getMaxBackoffMs()));
        body.put("delivery", stats.snapshot());
        body.put("queue", queueGuard.snapshot());
        body.put("pools", Map.of(
                "verification", poolView(verificationExecutor),
                "notification", poolView(notificationExecutor)));
        return ApiResponse.success(body);
    }

    private Map<String, Object> fromView() {
        MailChannelProperties.From from = properties.getFrom();
        Map<String, Object> view = new LinkedHashMap<>();
        // 未配置时置空字符串而不是 null：调用方不必区分「没配」和「没这个字段」
        view.put("verification", from.getVerification() == null ? "" : from.getVerification());
        view.put("notification", from.getNotification() == null ? "" : from.getNotification());
        view.put("bounce", from.getBounce() == null ? "" : from.getBounce());
        return view;
    }

    private Map<String, Object> channelView() {
        Map<String, Object> view = new LinkedHashMap<>();
        properties.getChannels().forEach((name, channel) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("host", channel.getHost());
            one.put("port", channel.getPort());
            one.put("enabled", channel.isEnabled());
            one.put("configured", channel.looksConfigured());
            one.put("domains", channel.getDomains());
            // 只暴露超时值，便于核对「是否真的配了超时」
            one.put("connection_timeout_ms", channel.getConnectionTimeoutMs());
            one.put("read_timeout_ms", channel.getReadTimeoutMs());
            one.put("write_timeout_ms", channel.getWriteTimeoutMs());
            view.put(name, one);
        });
        return view;
    }

    private static Map<String, Object> poolView(ThreadPoolTaskExecutor executor) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("queue_capacity", executor.getQueueCapacity());
        try {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            view.put("pool_size", pool.getPoolSize());
            view.put("active", pool.getActiveCount());
            view.put("max_pool_size", pool.getMaximumPoolSize());
            view.put("queued", pool.getQueue().size());
            view.put("completed", pool.getCompletedTaskCount());
        } catch (IllegalStateException e) {
            // 池尚未初始化（例如极早期调用）时不让整个接口失败
            view.put("state", "not initialized");
        }
        return view;
    }
}
