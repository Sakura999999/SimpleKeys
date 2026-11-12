package com.simplecard.service;

import java.util.Map;
import java.util.UUID;

public interface PaymentService {

    /**
     * Create payment for an order.
     * Returns payment info: {order_id, payment_url, expires_at}
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount);

    /**
     * Create payment with device hint (pc/mobile/wechat/alipay).
     */
    Map<String, Object> createPayment(UUID orderId, String paymentMethod, java.math.BigDecimal amount, String device);

    /**
     * Re-initiate payment for a PENDING order (clears cached URLs, requests new payment link).
     * 归属校验由 {@link OrderAccessGuard} 统一执行（身份取自 RequestContext）。
     *
     * @param callerSessionToken 调用方请求头 X-Session-Token，用于访客订单归属判定
     */
    Map<String, Object> repay(UUID orderId, String device, String callerSessionToken);
}
