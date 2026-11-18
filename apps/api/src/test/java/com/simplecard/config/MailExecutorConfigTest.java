package com.simplecard.config;

import com.simplecard.service.mail.MailQueueGuard;
import com.simplecard.service.mail.MailPurpose;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 发信线程池的约束测试。
 *
 * <p>这里断言的不是「能跑」，而是三个容易在后续改动中被悄悄破坏的性质：
 * 池必须有界、队列必须有界、溢出时不能把异常抛回业务线程。
 */
class MailExecutorConfigTest {

    private final MailQueueGuard guard = new MailQueueGuard();
    private final MailExecutorConfig config = new MailExecutorConfig();

    @Test
    void verificationPoolIsBounded() {
        ThreadPoolTaskExecutor executor = config.mailVerificationExecutor(guard);
        try {
            assertEquals(4, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            assertEquals(500, executor.getQueueCapacity());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void notificationPoolIsBounded() {
        ThreadPoolTaskExecutor executor = config.mailNotificationExecutor(guard);
        try {
            assertEquals(4, executor.getCorePoolSize());
            assertEquals(2000, executor.getQueueCapacity());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 队列打满时：不抛异常（否则会把支付回调 / 查单接口拖挂），但要被计数。
     *
     * <p>{@code ThreadPoolTaskExecutor#execute} 在底层拒绝且 handler 抛出时，会包成
     * {@code TaskRejectedException} 抛给调用线程 —— 这正是要防的情况。
     */
    @Test
    void rejectedTaskIsCountedAndDoesNotThrow() throws Exception {
        ThreadPoolTaskExecutor executor = config.mailNotificationExecutor(guard);
        CountDownLatch gate = new CountDownLatch(1);
        Runnable blocking = () -> {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            int capacity = executor.getQueueCapacity() + executor.getMaxPoolSize();
            for (int i = 0; i < capacity; i++) {
                executor.execute(blocking);
            }
            // 池与队列都满了，这一封必然被拒绝
            executor.execute(blocking);

            assertEquals(1L, guard.snapshot().get("notification_rejected"));
            assertEquals(0L, guard.snapshot().get("verification_rejected"));
        } finally {
            gate.countDown();
            executor.shutdown();
        }
    }

    /** 两类信分开计数：用途决定「丢了要不要人管」 */
    @Test
    void rejectionIsCountedPerPurpose() {
        guard.recordRejection(MailPurpose.VERIFICATION, "test");
        guard.recordRejection(MailPurpose.VERIFICATION, "test");
        guard.recordRejection(MailPurpose.NOTIFICATION, "test");

        assertEquals(2L, guard.snapshot().get("verification_rejected"));
        assertEquals(1L, guard.snapshot().get("notification_rejected"));
        assertEquals(3L, guard.snapshot().get("total_rejected"));
    }

    @Test
    void emptyGuardReportsZeros() {
        assertEquals(0L, guard.snapshot().get("total_rejected"));
        assertEquals(0L, guard.snapshot().get("verification_rejected"));
        assertEquals(0L, guard.snapshot().get("notification_rejected"));
    }
}
