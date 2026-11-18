package com.simplecard.service.mail;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 发信结果统计与硬退抑制。
 *
 * <p><b>为什么必须抑制硬退地址：</b>向不存在或已停用的地址反复发信，每发一次服务商就
 * 记一次硬退。硬退率超过阈值（SES 是 5%、多数服务商 2%~5%）会连带封掉整个发件域，
 * 那时连正常用户都收不到信。所以识别到"收件人永久拒绝"后，必须记住这个地址、不再重发。
 *
 * <p><b>抑制期为何是 30 天而非永久：</b>用户可能重新启用了邮箱，或者当初是服务商误判。
 * 永久封禁会让这些用户永久失去找回订单的能力，且没有任何自救途径。
 *
 * <p>统计走 micrometer（项目已有 {@code MeterRegistry}），内存计数用于运维快照。
 */
@Slf4j
@Component
public class MailDeliveryStats {

    /** 硬退抑制时长 */
    static final long SUPPRESS_TTL_MS = 30L * 24 * 60 * 60 * 1000;
    /** 抑制表容量上限，防止被大量随机地址撑爆内存 */
    static final int MAX_SUPPRESSED = 100_000;

    private final MeterRegistry meterRegistry;

    /** 归一化邮箱 → 过期时间戳 */
    private final Map<String, Long> suppressed = new ConcurrentHashMap<>();

    private final LongAdder sent = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder suppressedSkips = new LongAdder();
    /** 需要人介入的失败次数（认证失败、收件人拒绝等），用于「该告警」的量化口径 */
    private final LongAdder alertRequired = new LongAdder();
    /** 失败分类计数，reason → 次数 */
    private final Map<String, LongAdder> failureBreakdown = new ConcurrentHashMap<>();

    public MailDeliveryStats(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 该地址是否处于硬退抑制期 */
    public boolean isSuppressed(String email) {
        String key = normalize(email);
        if (key == null) {
            return false;
        }
        Long until = suppressed.get(key);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            suppressed.remove(key);
            return false;
        }
        suppressedSkips.increment();
        return true;
    }

    /** 记录一次发送结果 */
    public void record(String toEmail, MailPurpose purpose, MailDeliveryResult result) {
        String channel = result.channel() == null ? "none" : result.channel();
        String purposeTag = purpose == null ? "unknown" : purpose.name().toLowerCase();

        if (result.success()) {
            sent.increment();
            meterRegistry.counter("mail.sent", "channel", channel, "purpose", purposeTag).increment();
            meterRegistry.counter("mail.outcome", "channel", channel, "result", "success").increment();
            return;
        }

        failed.increment();
        String reason = result.failure() == null ? "UNKNOWN" : result.failure().name();
        failureBreakdown.computeIfAbsent(reason, k -> new LongAdder()).increment();
        meterRegistry.counter("mail.outcome", "channel", channel, "result", "failure", "reason", reason).increment();

        if (result.failure() == MailFailureType.RECIPIENT_REJECTED) {
            suppress(toEmail, result.detail());
        }
        if (result.shouldAlert()) {
            alertRequired.increment();
            log.warn("Mail delivery needs attention: channel={} reason={} to={} detail={}",
                    channel, reason, mask(toEmail), result.detail());
        }
    }

    /** 把地址加入硬退抑制表 */
    public void suppress(String email, String reason) {
        String key = normalize(email);
        if (key == null) {
            return;
        }
        if (suppressed.size() >= MAX_SUPPRESSED && !suppressed.containsKey(key)) {
            // 简单保护：满了就整体保留，拒绝新增，避免被刷爆内存；
            // 真实场景下 10 万条硬退已经说明发信源有问题，该修的是源头
            log.warn("Hard-bounce suppression table full ({}), skip adding {}", MAX_SUPPRESSED, mask(email));
            return;
        }
        boolean firstTime = suppressed.put(key, System.currentTimeMillis() + SUPPRESS_TTL_MS) == null;
        if (firstTime) {
            log.info("Suppress future mail to {} for 30d (hard bounce: {})", mask(email), reason);
        }
    }

    /** 运维快照，可挂到健康检查或管理端 */
    public Map<String, Object> snapshot() {
        long ok = sent.sum();
        long bad = failed.sum();
        long total = ok + bad;
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("sent", ok);
        snap.put("failed", bad);
        snap.put("success_rate", total == 0 ? null : Math.round(ok * 10000.0 / total) / 100.0);
        snap.put("suppressed_addresses", suppressed.size());
        snap.put("suppressed_skips", suppressedSkips.sum());
        snap.put("alert_required", alertRequired.sum());
        Map<String, Long> breakdown = new LinkedHashMap<>();
        failureBreakdown.forEach((k, v) -> breakdown.put(k, v.sum()));
        snap.put("failure_breakdown", breakdown);
        return snap;
    }

    private static String normalize(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<empty>";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
