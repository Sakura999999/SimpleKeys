package com.simplecard.service.impl;

import com.simplecard.constant.CardKeyStatus;
import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import com.simplecard.service.DeliverService;
import com.simplecard.service.OrderAccessGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverServiceImpl implements DeliverService {

    private final OrderRepository orderRepository;
    private final VerifiedPaymentEventRepository paymentEvents;
    private final com.simplecard.service.InventoryLifecycleService inventoryLifecycle;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final UnmatchedTransactionRepository unmatchedTransactionRepository;
    private final OrderAccessGuard orderAccessGuard;

    @Override
    @SuppressWarnings("unchecked")
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
                try { inventoryLifecycle.close(o.getId(), false); }
                catch (com.simplecard.exception.BusinessException busy) {
                    if (busy.getHttpStatus() != org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) throw busy;
                }
                o.setStatus(orderRepository.findById(o.getId()).orElseThrow().getStatus());
            }
        }

        // Sort by createdAt desc
        orders.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        return orders.stream().map(o -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", o.getId());
            map.put("total_amount", o.getTotalAmount());
            map.put("actual_amount", o.getActualAmount());
            map.put("currency", "CNY");
            map.put("payment_currency",o.getPaymentCurrency());
            map.put("payment_amount",o.getPaymentAmount());
            map.put("status", o.getStatus().name());
            map.put("payment_processing", paymentEvents.existsByOrderIdAndProcessedFalse(o.getId()));
            map.put("payment_review_reason", o.getPaymentReviewReason());
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
        // Read only the cards assigned by the confirmed-payment transaction.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // 授权校验 — 此端点会返回卡密明文，注册账号的订单必须由本人访问
        orderAccessGuard.assertAccess(order, callerSessionToken);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", orderId);

        result.put("status", order.getStatus().name());
        result.put("payment_review_reason", order.getPaymentReviewReason());
        result.put("groups", order.getStatus() == OrderStatus.DELIVERED ? buildCardKeyGroups(orderId) : List.of());
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

        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId).stream().filter(k -> k.getStatus() == CardKeyStatus.SOLD).toList();
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
        List<CardKey> keys = cardKeyRepository.findByOrderId(orderId).stream().filter(k -> k.getStatus() == CardKeyStatus.SOLD).toList();
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

}
