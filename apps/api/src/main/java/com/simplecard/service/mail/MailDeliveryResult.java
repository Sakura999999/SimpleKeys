package com.simplecard.service.mail;

/**
 * 一次发送的结果。
 *
 * @param success  是否投递成功（SMTP 已接收）
 * @param channel  实际生效的通道名；降级时为最终成功的那个
 * @param failure  失败分类；成功时为 {@code null}
 * @param attempts 总尝试次数（含首次）
 * @param detail   失败摘要，已脱敏，可直接写日志
 */
public record MailDeliveryResult(
        boolean success,
        String channel,
        MailFailureType failure,
        int attempts,
        String detail
) {
    public static MailDeliveryResult ok(String channel, int attempts) {
        return new MailDeliveryResult(true, channel, null, attempts, null);
    }

    public static MailDeliveryResult fail(String channel, MailFailureType failure, int attempts, String detail) {
        return new MailDeliveryResult(false, channel, failure, attempts, detail);
    }

    /** 未尝试发送（功能关闭、地址被抑制、无可用通道）。不计入成功率统计 */
    public static MailDeliveryResult skipped(String detail) {
        return new MailDeliveryResult(false, null, null, 0, detail);
    }

    /** 是否属于「未尝试」，用于把跳过与真实失败分开统计 */
    public boolean isSkipped() {
        return failure == null && attempts == 0;
    }

    /** 是否应对本次失败告警 */
    public boolean shouldAlert() {
        return !success && failure != null && failure.needsAlert();
    }
}
