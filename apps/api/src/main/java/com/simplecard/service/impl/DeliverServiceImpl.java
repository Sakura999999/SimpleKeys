package com.simplecard.service.impl;

import com.simplecard.constant.CardKeyStatus;
import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import com.simplecard.service.DeliverService;
import com.simplecard.service.EmailService;
import com.simplecard.service.OrderAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverServiceImpl implements DeliverService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final UserRepository userRepository;
    private final PointsLogRepository pointsLogRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final UnmatchedTransactionRepository unmatchedTransactionRepository;
    private final EmailService emailService;
    private final OrderAccessGuard orderAccessGuard;

    @Override
    @SuppressWarnings("unchecked")
    @Transactional
    public List<?> queryOrders(Map<String, Object> request, String callerSessionToken) {
        List<Order> orders = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();

        // 按订单号查询：订单号是能力凭证，但属于注册账号的订单仍需本人身份
        List<String> orderIdStrs = (List<String>) request.get("order_ids");
        if (orderIdStrs != null) {
            for (String idStr : orderIdStrs) {
                UUID id = parseOrderId(idStr);
                if (!seen.add(id)) continue;
                orderRepository.findById(id).ifPresent(o -> {
                    orderAccessGuard.assertAccess(o, callerSessionToken);
                    orders.add(o);
                });
            }
        }

        // 按邮箱查询：邮箱不是身份凭证，只作为过滤条件。
        // 仅返回调用方能证明归属的订单，否则此路径会变成订单号枚举入口（可进而通过
        // /orders/deliver 取得他人卡密）。跨设备邮箱查单需邮箱验证码，尚未实现。
        List<String> emails = (List<String>) request.get("emails");
        if (emails != null && !emails.isEmpty()) {
            List<Order> emailOrders = orderRepository.findByEmailInOrderByCreatedAtDesc(emails);
            for (Order o : emailOrders) {
                if (!orderAccessGuard.ownsOrder(o, callerSessionToken)) continue;
                if (!seen.add(o.getId())) continue;
                orders.add(o);
            }
        }

        // 用 orders 而非 seen 判断：请求了不存在的订单号时 seen 非空但 orders 为空。
        // 不区分"不存在"与"非本人"，避免成为订单存在性探测口。
        if (orders.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }

        // 主动过期检查：PENDING 且已超时的订单标记为 EXPIRED（与 getOrderStatus 逻辑一致）
        LocalDateTime now = LocalDateTime.now();
        for (Order o : orders) {
            if (o.getStatus() == OrderStatus.PENDING && o.getExpiresAt().isBefore(now)) {
                o.setStatus(OrderStatus.EXPIRED);
                orderRepository.save(o);
            }
        }

        // Sort by createdAt desc
        orders.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        return orders.stream().map(o -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", o.getId());
            map.put("total_amount", o.getTotalAmount());
            map.put("actual_amount", o.getActualAmount());
            map.put("status", o.getStatus().name());
            map.put("order_type", o.getOrderType().name());
            map.put("payment_method", o.getPaymentMethod());
            map.put("created_at", o.getCreatedAt());
            // USDT 订单：附带 TXID 审核状态（供用户查询页展示审核结果反馈）
            if (o.getPaymentMethod() != null && o.getPaymentMethod().startsWith("usdt_")) {
                map.put("usdt_tx_id", o.getUsdtTxId());
                List<UnmatchedTransaction> reviews = unmatchedTransactionRepository.findByOrderId(o.getId());
                if (!reviews.isEmpty()) {
                    // 取最新一条（按提交时间倒序）
                    UnmatchedTransaction latest = reviews.stream()
                            .max(java.util.Comparator.comparing(UnmatchedTransaction::getSubmittedAt))
                            .orElse(reviews.get(0));
                    map.put("txid_review_status", latest.getStatus());
                    map.put("txid_review_reason", latest.getVerifyReason());
                }
            }
            return map;
        }).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    @Transactional
    public List<?> deliverOrders(Map<String, Object> request, String callerSessionToken) {
        List<String> orderIdStrs = (List<String>) request.get("order_ids");
        if (orderIdStrs == null || orderIdStrs.isEmpty()) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (String idStr : orderIdStrs) {
            UUID orderId = parseOrderId(idStr);
            results.add(deliverSingleOrder(orderId, callerSessionToken));
        }
        return results;
    }

    /**
     * 解析订单号，非法格式返回 400 而非 500。
     * 这两个端点均为 permitAll，此前直接 UUID.fromString 抛出的 IllegalArgumentException
     * 会被全局兜底处理器转成 500 "System error"，任何人传个畸形 order_ids 就能刷 500。
     */
    private UUID parseOrderId(String idStr) {
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单号格式不正确");
        }
    }

    private Map<String, Object> deliverSingleOrder(UUID orderId, String callerSessionToken) {
        // F2: 悲观锁 — 防止同一 PAID 订单被并发请求重复发货分配双份卡密
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // 授权校验 — 此端点会返回卡密明文，注册账号的订单必须由本人访问
        orderAccessGuard.assertAccess(order, callerSessionToken);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", orderId);

        switch (order.getStatus()) {
            case DELIVERED -> {
                result.put("status", "DELIVERED");
                result.put("groups", buildCardKeyGroups(orderId));
            }
            case PAID -> {
                List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
                // F17: 发货前校验所有订单项数量，异常时立即标记 EXPIRED，防止前端反复重试
                for (OrderItem item : items) {
                    if (item.getQuantity() <= 0) {
                        log.error("Order {} contains item with invalid quantity={}, marking EXPIRED to block retries",
                                orderId, item.getQuantity());
                        order.setStatus(OrderStatus.EXPIRED);
                        orderRepository.save(order);
                        result.put("status", "EXPIRED");
                        result.put("groups", List.of());
                        return result;
                    }
                }
                // 两阶段分配 — 先全部锁定，确认无缺口后才写入。
                //
                // 旧实现边锁边写：多品项订单中若后面的品项缺货，抛出的 BusinessException 在本方法内
                // 就被 catch 掉，从未逸出 @Transactional 边界，Spring 不会标记回滚，前面品项已分配的
                // 卡密照常提交落库，而订单仍停在 PAID。下次重试会为同一品项再分配一批，
                // buildCardKeyGroups 依 orderId 取出全部，客户拿到成倍卡密且库存被持续消耗。
                //
                // 现在阶段一只做 FOR UPDATE SKIP LOCKED 查询（不产生任何写入），任一品项不足即
                // 直接返回，事务无写入可提交；阶段二才统一落库，保证全有或全无。
                Map<OrderItem, List<CardKey>> allocation = new LinkedHashMap<>();
                OrderItem shortItem = null;
                for (OrderItem item : items) {
                    List<CardKey> keys = cardKeyRepository.findAndLockAvailable(
                            item.getProductId(), item.getSpecId(), item.getQuantity());
                    if (keys.size() < item.getQuantity()) {
                        shortItem = item;
                        break;
                    }
                    allocation.put(item, keys);
                }

                if (shortItem != null) {
                    log.warn("Deliver deferred for order {}: item {} short of stock (need {}), no card keys allocated",
                            orderId, shortItem.getProductTitle(), shortItem.getQuantity());
                    result.put("status", "PAID");
                    result.put("groups", List.of());
                    return result;
                }

                LocalDateTime soldAt = LocalDateTime.now();
                for (Map.Entry<OrderItem, List<CardKey>> entry : allocation.entrySet()) {
                    for (CardKey key : entry.getValue()) {
                        key.setStatus(CardKeyStatus.SOLD);
                        key.setOrderId(orderId);
                        key.setOrderItemId(entry.getKey().getId());
                        key.setSoldAt(soldAt);
                        cardKeyRepository.save(key);
                    }
                }

                order.setStatus(OrderStatus.DELIVERED);
                order.setDeliveredAt(LocalDateTime.now());
                orderRepository.save(order);

                // Award points
                awardPoints(order);

                // Send delivery email after transaction commits (async, non-blocking)
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        emailService.sendDeliveryEmail(orderId);
                    }
                });

                result.put("status", "DELIVERED");
                result.put("groups", buildCardKeyGroups(orderId));
            }
            case PENDING -> {
                result.put("status", "PENDING");
                result.put("groups", List.of());
            }
            case EXPIRED -> {
                result.put("status", "EXPIRED");
                result.put("groups", List.of());
            }
        }
        return result;
    }

    @Override
    public String exportCardKeys(UUID orderId, String callerSessionToken) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // 授权校验 — 此端点直接下载卡密全文，此前完全没有归属检查
        orderAccessGuard.assertAccess(order, callerSessionToken);

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID, "订单未发货");
        }

        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = items.stream().collect(Collectors.toMap(OrderItem::getId, i -> i));

        StringBuilder sb = new StringBuilder();
        sb.append("订单号: ").append(orderId).append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(k -> k.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId));

        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item != null) {
                sb.append("商品: ").append(item.getProductTitle());
                if (item.getSpecName() != null) sb.append(" [").append(item.getSpecName()).append("]");
                sb.append("\n");
                for (CardKey key : entry.getValue()) {
                    sb.append(key.getContent()).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildCardKeyGroups(UUID orderId) {
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId);
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        Map<UUID, OrderItem> itemMap = items.stream().collect(Collectors.toMap(OrderItem::getId, i -> i));

        Map<UUID, List<CardKey>> grouped = keys.stream()
                .filter(k -> k.getOrderItemId() != null)
                .collect(Collectors.groupingBy(CardKey::getOrderItemId));

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item != null) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("product_title", item.getProductTitle());
                g.put("spec_name", item.getSpecName());
                g.put("card_keys", entry.getValue().stream().map(CardKey::getContent).toList());
                groups.add(g);
            }
        }
        return groups;
    }

    private void awardPoints(Order order) {
        if (order.getUserId() == null) return;
        boolean pointsEnabled = siteConfigRepository.findByConfigKey("points_enabled")
                .map(c -> "true".equalsIgnoreCase(c.getConfigValue()))
                .orElse(false);
        if (!pointsEnabled) return;

        int pointsRate = siteConfigRepository.findByConfigKey("points_rate")
                .map(c -> { try { return Integer.parseInt(c.getConfigValue()); } catch (Exception e) { return 0; } })
                .orElse(0);
        if (pointsRate <= 0) return;

        int pointsEarned = order.getActualAmount().multiply(java.math.BigDecimal.valueOf(pointsRate))
                .setScale(0, java.math.RoundingMode.FLOOR).intValue();
        if (pointsEarned <= 0) return;

        // 加行锁读取 —— 同一用户的多笔订单并发发货时，无锁的读改写会丢失更新，
        // 且 PointsLog.balanceAfter 会与 users.points 实际值不一致。
        User user = userRepository.findByIdForUpdate(order.getUserId()).orElse(null);
        if (user == null) return;

        user.setPoints(user.getPoints() + pointsEarned);
        userRepository.save(user);

        PointsLog log = new PointsLog();
        log.setUserId(user.getId());
        log.setChangeAmount(pointsEarned);
        log.setBalanceAfter(user.getPoints());
        log.setReason("购物奖励积分");
        log.setOrderId(order.getId());
        pointsLogRepository.save(log);
    }
}
