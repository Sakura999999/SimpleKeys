package com.simplecard.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 访客订单找回的一次性验证码存储。
 *
 * <p><b>为什么放内存而不是数据库：</b>验证码与查单凭证都是短命、可丢弃、无审计价值的
 * 临时状态，写库会为每个访客引入一次写放大，还会让「重启」变成一个必须清理的脏数据问题。
 * 本项目为单实例部署（docker-compose 无 replicas），进程重启后用户重收一次验证码即可，
 * 代价可接受。若将来改为多实例，这里必须换成 Redis 并保留同样的原子消费语义。
 *
 * <p><b>并发唯一性：</b>消费走 {@link ConcurrentHashMap#compute}，对同一邮箱键串行执行，
 * 天然保证「同一验证码并发提交只成功一次」；{@link AtomicBoolean} 的 CAS 是第二道保险，
 * 防止将来改成别的存储实现时丢掉这个性质。
 *
 * <p><b>不存明文：</b>只存 {@code sha256(进程级随机盐 + 邮箱 + 验证码)}。验证码空间只有
 * 10^6，不加盐的哈希等于明文，加盐后即使内存转储也无法直接反查。
 */
@Slf4j
@Component
public class EmailVerificationCodeStore {

    /** 消费结果。对外只暴露「成功/失败」，具体原因仅用于服务端日志与限流决策。 */
    public enum Outcome {
        OK, MISSING, EXPIRED, MISMATCH, TOO_MANY_ATTEMPTS, ALREADY_CONSUMED
    }

    public record ConsumeResult(Outcome outcome, int attemptsLeft) {
        public boolean ok() { return outcome == Outcome.OK; }
    }

    /** 发送决策。allowed=false 时 retryAfterSeconds>0 表示还需等待多久。 */
    public record SendDecision(boolean allowed, long retryAfterSeconds, String reason) {
        static SendDecision allow(long retryAfterSeconds) { return new SendDecision(true, retryAfterSeconds, "OK"); }
        static SendDecision deny(long retryAfterSeconds, String reason) { return new SendDecision(false, retryAfterSeconds, reason); }
    }

    private record CodeEntry(String codeHash, long sentAt, long expiresAt,
                             AtomicInteger attempts, AtomicBoolean consumed) {}

    /** 发送配额与验证码分开保存：验证码被消费/销毁后，重发间隔与每日上限仍然有效。 */
    private record SendState(long epochDay, int count, long lastSentAt) {}

    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    private final Map<String, SendState> sends = new ConcurrentHashMap<>();
    private final byte[] salt = new byte[32];

    public EmailVerificationCodeStore() {
        new SecureRandom().nextBytes(salt);
    }

    /**
     * 原子地完成「配额检查 + 签发验证码」。两者必须在同一个 compute 内，
     * 否则并发的两次点击会各自通过检查并发出两封邮件。
     *
     * @param emailKey            规范化后的邮箱（小写、去空白）
     * @param code                明文验证码；传 null 表示<b>只占用发送配额、不签发验证码</b>。
     *                            该邮箱没有可找回订单时用这种调用，使「有订单」与「无订单」两条
     *                            路径的配额消耗与响应完全一致，不给探测留下差异。
     * @param ttlSeconds          验证码有效期
     * @param resendIntervalSeconds 两次发送最小间隔
     * @param dailyLimit          同一邮箱每日发送上限
     */
    public SendDecision reserveAndIssue(String emailKey, String code, int ttlSeconds,
                                        int resendIntervalSeconds, int dailyLimit) {
        long now = System.currentTimeMillis();
        long today = LocalDate.now().toEpochDay();
        AtomicReference<SendDecision> decision = new AtomicReference<>();

        sends.compute(emailKey, (key, state) -> {
            SendState current = (state == null || state.epochDay() != today)
                    ? new SendState(today, 0, 0L)
                    : state;

            long sinceLast = now - current.lastSentAt();
            if (current.lastSentAt() > 0 && sinceLast < resendIntervalSeconds * 1000L) {
                long wait = resendIntervalSeconds - sinceLast / 1000L;
                decision.set(SendDecision.deny(Math.max(1, wait), "RESEND_TOO_SOON"));
                return current;
            }
            if (current.count() >= dailyLimit) {
                decision.set(SendDecision.deny(0, "DAILY_LIMIT"));
                return current;
            }
            decision.set(SendDecision.allow(resendIntervalSeconds));
            return new SendState(today, current.count() + 1, now);
        });

        if (decision.get() == null) {
            // 理论上不可达；保险起见按拒绝处理，绝不签发
            return SendDecision.deny(resendIntervalSeconds, "UNKNOWN");
        }
        if (decision.get().allowed() && code != null) {
            codes.put(emailKey, new CodeEntry(
                    hash(emailKey, code), now, now + ttlSeconds * 1000L,
                    new AtomicInteger(0), new AtomicBoolean(false)));
        }
        return decision.get();
    }

    /**
     * 原子消费验证码。
     *
     * <p>顺序刻意如此：先判存在与过期，再累加尝试次数，然后才比对。
     * 这样「正确验证码但已试错超限」会被拒绝——否则爆破者可以先错 4 次、
     * 第 5 次直接猜对，attempts 限制就形同虚设。
     */
    public ConsumeResult consume(String emailKey, String code, int maxAttempts) {
        long now = System.currentTimeMillis();
        AtomicReference<ConsumeResult> result = new AtomicReference<>();

        codes.compute(emailKey, (key, entry) -> {
            if (entry == null) {
                result.set(new ConsumeResult(Outcome.MISSING, 0));
                return null;
            }
            if (now > entry.expiresAt()) {
                result.set(new ConsumeResult(Outcome.EXPIRED, 0));
                return null;
            }
            if (entry.consumed().get()) {
                result.set(new ConsumeResult(Outcome.ALREADY_CONSUMED, 0));
                return null;
            }

            int attempt = entry.attempts().incrementAndGet();
            if (attempt > maxAttempts) {
                result.set(new ConsumeResult(Outcome.TOO_MANY_ATTEMPTS, 0));
                return null;
            }

            if (!MessageDigest.isEqual(hash(emailKey, code).getBytes(StandardCharsets.UTF_8),
                    entry.codeHash().getBytes(StandardCharsets.UTF_8))) {
                result.set(new ConsumeResult(Outcome.MISMATCH, maxAttempts - attempt));
                return entry;
            }

            // 同一邮箱键的 compute 已串行化，CAS 是防御未来换实现时丢失该性质
            if (!entry.consumed().compareAndSet(false, true)) {
                result.set(new ConsumeResult(Outcome.ALREADY_CONSUMED, 0));
                return null;
            }
            result.set(new ConsumeResult(Outcome.OK, 0));
            return null; // 消费即销毁
        });

        return result.get() == null ? new ConsumeResult(Outcome.MISSING, 0) : result.get();
    }

    /** 该邮箱下次可发送还需等待的秒数，0 表示可以立即发送。 */
    public long secondsUntilResend(String emailKey, int resendIntervalSeconds) {
        SendState state = sends.get(emailKey);
        if (state == null || state.lastSentAt() == 0) return 0;
        long elapsed = (System.currentTimeMillis() - state.lastSentAt()) / 1000L;
        return Math.max(0, resendIntervalSeconds - elapsed);
    }

    /** 测试与运维用途：清空全部状态。 */
    public void clear() {
        codes.clear();
        sends.clear();
    }

    public int pendingCodes() {
        return codes.size();
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }

    private String hash(String emailKey, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(emailKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
