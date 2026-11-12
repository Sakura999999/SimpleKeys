package com.simplecard.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public interface OrderService {
    SettlementService.Quote quote(Map<String,Object> request, UUID userId, String sessionToken);

    /**
     * 订单落库结果。
     *
     * <p>刻意<b>不</b>包含支付信息：调用支付网关是跨网络操作，最坏情况下
     * 3 次重试 ×（5 秒连接 + 15 秒读取）+ 2 秒退避 ≈ 62 秒。若在建单事务内完成，
     * 这段时间会一直占用 Hikari 连接（池大小 20，获取超时 10 秒），
     * 并发下单稍多即耗尽连接池，连支付回调都拿不到连接，形成全站故障。
     *
     * <p>因此建单事务只做数据库写入并立即提交，支付创建由调用方在事务外发起。
     */
    record CreatedOrder(UUID orderId,
                        String paymentMethod,
                        BigDecimal actualAmount,
                        String device,
                        Map<String, Object> orderDetail) {}

    CreatedOrder createDirectOrder(Map<String, Object> request, UUID userId, String clientIp, String sessionToken);

    CreatedOrder createCartOrder(Map<String, Object> request, UUID userId, String clientIp, String sessionToken);

    /**
     * 支付渠道下单失败时作废订单。
     *
     * <p>建单与支付创建拆开后，网关失败不再连带回滚订单，会留下一笔永远无法支付的 PENDING 订单。
     * 这类订单会占用 {@code max_pending_orders_per_user} 配额 —— 网关故障期间用户重试几次
     * 就会被自己的失败订单锁在门外。标记为 EXPIRED 可保持与拆分前一致的用户可见行为。
     */
    void markPaymentSetupFailed(UUID orderId);

    Map<String, Object> getOrderStatus(UUID orderId);

    Map<String, Object> refreshOrderStatus(UUID orderId);

    void expireOrders();
}
