package com.simplecard.service.mail;

/**
 * 发信失败分类 — 决定「要不要重试」「要不要换通道」「要不要告警」。
 *
 * <p>三者必须分开判断，否则会把「收件人邮箱不存在」当成网络故障反复重试，
 * 每重试一次就给发件域累积一次硬退，最终被服务商判定为滥发并封域。
 */
public enum MailFailureType {

    /** 认证失败：账号/授权码错，或服务商禁用了 SMTP。换通道可能成功，需人工告警 */
    AUTH_FAILED(true, true),

    /** 连接失败或超时：网络、DNS、端口封闭、服务端无响应。换通道可能成功 */
    CONNECT_FAILED(true, true),

    /** 被服务商限流：换通道可绕开，或本通道退避后重试 */
    RATE_LIMITED(true, true),

    /** 收件人不存在或被拒（5xx 永久错误）。重试与换通道都无意义，且会累积硬退 */
    RECIPIENT_REJECTED(false, false),

    /** 内容被判定为垃圾或违规。需要改模板或申请白名单，与通道无关 */
    CONTENT_REJECTED(false, false),

    /** 其他未知错误。保守处理：只重试一次，不换通道，避免放大问题 */
    UNKNOWN(false, false);

    private final boolean retryable;
    private final boolean channelSwappable;

    MailFailureType(boolean retryable, boolean channelSwappable) {
        this.retryable = retryable;
        this.channelSwappable = channelSwappable;
    }

    /** 同通道退避重试是否有意义 */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 换通道是否有意义。
     *
     * <p>只在「确定没投递成功」时允许换通道 —— 认证失败与连接失败都发生在投递之前，
     * 限流是服务端明确拒绝。收件人层面的拒绝换通道也一样，所以不换。
     */
    public boolean channelSwappable() {
        return channelSwappable;
    }

    /** 需要人工关注的分类（配置或信誉问题，不会自愈） */
    public boolean needsAlert() {
        return this == AUTH_FAILED || this == RECIPIENT_REJECTED || this == CONTENT_REJECTED;
    }
}
