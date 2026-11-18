package com.simplecard;

import com.simplecard.service.EmailVerificationCodeStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证码存储的行为契约。
 *
 * <p>这些断言对应验收重点里的「同一验证码并发提交，只能消费成功一次」，
 * 以及几条容易被后续重构悄悄破坏的性质。
 */
class EmailVerificationCodeStoreTest {

    private static final String EMAIL = "buyer@example.com";

    @Test
    void concurrentConsumeSucceedsExactlyOnce() throws Exception {
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        assertThat(store.reserveAndIssue(EMAIL, "123456", 600, 60, 10).allowed()).isTrue();

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return store.consume(EMAIL, "123456", 5).ok();
                }));
            }
            start.countDown();
            long successes = 0;
            for (Future<Boolean> call : calls) {
                if (call.get(10, TimeUnit.SECONDS)) successes++;
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void wrongCodeIsCountedAndCodeIsDestroyedAfterTheLimit() {
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        store.reserveAndIssue(EMAIL, "123456", 600, 60, 10);

        // maxAttempts=2：第 2 次仍算「码不对」，第 3 次直接判超限并销毁
        assertThat(store.consume(EMAIL, "000000", 2).outcome())
                .isEqualTo(EmailVerificationCodeStore.Outcome.MISMATCH);
        assertThat(store.consume(EMAIL, "111111", 2).outcome())
                .isEqualTo(EmailVerificationCodeStore.Outcome.MISMATCH);
        assertThat(store.consume(EMAIL, "123456", 2).outcome())
                .isEqualTo(EmailVerificationCodeStore.Outcome.TOO_MANY_ATTEMPTS);

        // 已销毁：即便给出正确码也只能重新获取
        assertThat(store.consume(EMAIL, "123456", 2).outcome())
                .isEqualTo(EmailVerificationCodeStore.Outcome.MISSING);
    }

    @Test
    void expiredCodeIsRejected() {
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        store.reserveAndIssue(EMAIL, "123456", -1, 60, 10);
        assertThat(store.consume(EMAIL, "123456", 5).outcome())
                .isEqualTo(EmailVerificationCodeStore.Outcome.EXPIRED);
    }

    @Test
    void resendIntervalIsEnforcedAndQuotaIsCounted() {
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        assertThat(store.reserveAndIssue(EMAIL, "123456", 600, 60, 10).allowed()).isTrue();

        EmailVerificationCodeStore.SendDecision second =
                store.reserveAndIssue(EMAIL, "654321", 600, 60, 10);
        assertThat(second.allowed()).isFalse();
        assertThat(second.reason()).isEqualTo("RESEND_TOO_SOON");
        assertThat(second.retryAfterSeconds()).isGreaterThan(0);

        assertThat(store.secondsUntilResend(EMAIL, 60)).isGreaterThan(0);
    }

    @Test
    void dailyLimitBlocksFurtherSendsEvenWithoutResendInterval() {
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        // resendInterval=0 让每次都能立即重发，从而验证「日上限」这一独立约束
        assertThat(store.reserveAndIssue(EMAIL, "1", 600, 0, 2).allowed()).isTrue();
        assertThat(store.reserveAndIssue(EMAIL, "2", 600, 0, 2).allowed()).isTrue();

        EmailVerificationCodeStore.SendDecision third = store.reserveAndIssue(EMAIL, "3", 600, 0, 2);
        assertThat(third.allowed()).isFalse();
        assertThat(third.reason()).isEqualTo("DAILY_LIMIT");
    }

    @Test
    void reservingWithoutACodeStillConsumesQuota() {
        // 无订单的邮箱走这条路径：占配额但不签发验证码，
        // 使「有订单」与「无订单」的配额消耗一致，避免配额耗尽与否成为探测信号
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        assertThat(store.reserveAndIssue(EMAIL, null, 600, 60, 10).allowed()).isTrue();
        assertThat(store.pendingCodes()).isZero();
        assertThat(store.consume(EMAIL, "123456", 5).outcome())
                .isEqualTo(EmailVerificationCodeStore.Outcome.MISSING);
    }

    @Test
    void differentEmailsDoNotInterfere() {
        EmailVerificationCodeStore store = new EmailVerificationCodeStore();
        store.reserveAndIssue("a@example.com", "111111", 600, 60, 10);
        store.reserveAndIssue("b@example.com", "222222", 600, 60, 10);

        assertThat(store.consume("a@example.com", "222222", 5).ok()).isFalse();
        assertThat(store.consume("a@example.com", "111111", 5).ok()).isTrue();
        assertThat(store.consume("b@example.com", "222222", 5).ok()).isTrue();
    }
}
