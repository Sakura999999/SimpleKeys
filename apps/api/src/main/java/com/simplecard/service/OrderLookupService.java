package com.simplecard.service;

/**
 * 访客订单找回 — 邮箱验证码与查单凭证。
 *
 * <p><b>安全前提：</b>邮箱不是身份凭证，只有「能读取该邮箱收到的验证码」才能证明控制权。
 * 因此本服务只做一件事：把「控制某邮箱」转换为一张短命凭证，
 * 由 {@link OrderAccessGuard} 决定这张凭证能打开哪些订单。
 *
 * <p><b>防枚举：</b>{@link #sendCode} 无论该邮箱有无订单，响应体、HTTP 状态、配额消耗
 * 都完全一致；有订单才真正发信（省邮件配额、避免被当作发信通道），
 * 但「有没有收到信」这个信息只对被查询邮箱的拥有者可见，不构成对外泄露。
 */
public interface OrderLookupService {

    /** 发送结果。刻意只返回重发间隔，不返回「是否已发送」。 */
    record SendResult(long retryAfterSeconds) {}

    /**
     * 验证结果。
     *
     * @param token            查单凭证，客户端应放入 sessionStorage 并以 X-Order-Lookup-Token 发送
     * @param expiresAtMillis  凭证到期时间（epoch millis）
     * @param guestOrders      该邮箱下可访问的访客订单数
     * @param registeredOrders 该邮箱下属于注册账号的订单数，仅用于提示「请登录查看」
     */
    record VerifyResult(String token, long expiresAtMillis, int guestOrders, int registeredOrders) {}

    /** 功能是否真正可用（后台开关打开 且 SMTP 已配置） */
    boolean isAvailable();

    SendResult sendCode(String rawEmail, String clientIp);

    VerifyResult verifyCode(String rawEmail, String code, String clientIp);
}
