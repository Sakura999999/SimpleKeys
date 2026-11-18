package com.simplecard.config;

import com.simplecard.service.mail.MailPurpose;
import com.simplecard.service.mail.MailQueueGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 发信专用线程池 — 让「验证码」和「发货通知」互不挤占。
 *
 * <p><b>为什么必须分开：</b>在此之前全站 {@code @Async} 共用 Spring Boot 默认的
 * {@code applicationTaskExecutor}（8 线程、<b>无界队列</b>）。发信虽然已加上 SMTP 超时，
 * 但单封最坏仍要 5s 连接 + 10s 读 + 10s 写 ≈ 25s，且验证码请求量随时可能被刷。
 * 结果是：<b>验证码洪峰会占满线程并让队列无限膨胀，排在其后的发货通知只能等着</b>——
 * 用户付了钱拿不到卡密，比收不到验证码严重得多。
 *
 * <p><b>两个池的分工：</b>
 * <table border="1">
 *   <tr><th>池</th><th>线程</th><th>队列</th><th>溢出策略</th></tr>
 *   <tr><td>verification</td><td>4</td><td>500</td><td>丢弃 + WARN（用户可重发）</td></tr>
 *   <tr><td>notification</td><td>4</td><td>2000</td><td>丢弃 + ERROR（需人工补发）</td></tr>
 * </table>
 *
 * <p><b>为什么溢出时选择丢弃而不是 CallerRuns：</b>发信调用点都在下单 / 支付回调 / 查单
 * 这些业务线程上。CallerRuns 会让业务线程自己去跑 SMTP 握手，最坏 25s——把发信的拥塞
 * 变成了接口超时，还会给「邮箱是否有订单」制造可观测的时序差。丢弃的代价是丢一封信，
 * CallerRuns 的代价是丢一次交易。
 *
 * <p><b>为什么有界：</b>无界队列等于把内存当缓冲。洪峰时队列只会越长越长，最后不是
 * 内存被打爆就是延迟高到邮件已无意义（验证码 10 分钟过期）。有界 + 计数，压力直接可见。
 */
@Slf4j
@Configuration
public class MailExecutorConfig {

    /** 验证码发信池 */
    public static final String VERIFICATION_EXECUTOR = "mailVerificationExecutor";
    /** 发货通知发信池 */
    public static final String NOTIFICATION_EXECUTOR = "mailNotificationExecutor";

    @Bean(VERIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor mailVerificationExecutor(MailQueueGuard guard) {
        return build("mail-verify-", 4, 500, MailPurpose.VERIFICATION, guard);
    }

    @Bean(NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor mailNotificationExecutor(MailQueueGuard guard) {
        return build("mail-notify-", 4, 2000, MailPurpose.NOTIFICATION, guard);
    }

    private ThreadPoolTaskExecutor build(String prefix, int threads, int queueCapacity,
                                         MailPurpose purpose, MailQueueGuard guard) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(discardHandler(purpose, queueCapacity, guard));
        // 关服时把手上这几封发完，避免优雅停机丢信
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 丢弃策略：只记录，不抛。
     *
     * <p>JDK 自带的 {@code DiscardPolicy} 是静默的，信丢了无痕；这里替换成
     * 「计数 + 分级日志」，效果同样是调用线程无感，但丢失量可在
     * {@code /api/admin/mail/delivery-stats} 上看到。
     */
    private static RejectedExecutionHandler discardHandler(MailPurpose purpose, int capacity,
                                                          MailQueueGuard guard) {
        return (Runnable task, ThreadPoolExecutor pool) -> guard.recordRejection(purpose,
                "pool=" + pool.getPoolSize() + ", queue=" + capacity);
    }
}
