package com.simplecard.service.impl;

import com.simplecard.constant.CardKeyStatus;
import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.constant.OrderType;
import com.simplecard.entity.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import com.simplecard.service.OrderService;
import com.simplecard.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final VerifiedPaymentEventRepository paymentEvents;
    private final com.simplecard.service.InventoryLifecycleService inventoryLifecycle;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ProductSpecRepository productSpecRepository;
    private final WholesaleRuleRepository wholesaleRuleRepository;
    private final CartItemRepository cartItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final PaymentChannelRepository paymentChannelRepository;
    private final SettlementService settlementService;

    @Override
    @Transactional(readOnly = true)
    public SettlementService.Quote quote(Map<String,Object> request, UUID userId, String sessionToken) {
        String method = optionalString(request,"payment_method");
        if (Boolean.TRUE.equals(request.get("from_cart"))) return quoteCart(loadCart(userId,sessionToken),method);
        UUID id = requireUuid(request,"product_id");
        UUID specId = optionalUuid(request,"spec_id");
        int quantity = requireInt(request,"quantity");
        Product product = loadProduct(id);
        return settlementService.quote(List.of(source(id.toString(),product,specId,quantity)),method);
    }

    private Product loadProduct(UUID id) {
        return productRepository.findById(id).filter(p -> p.getIsDeleted()==0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,"商品不存在或已下架"));
    }

    private List<CartItem> loadCart(UUID userId, String sessionToken) {
        if (userId != null) return cartItemRepository.findByUserId(userId);
        if (sessionToken != null) return cartItemRepository.findBySessionToken(sessionToken);
        return List.of();
    }

    private SettlementService.Source source(String key, Product product, UUID specId, int quantity) {
        if (quantity < 1 || quantity > getMaxPurchasePerUser()) throw new BusinessException(ErrorCode.BAD_REQUEST,"购买数量无效");
        validateSpecConsistency(product,specId);
        return new SettlementService.Source(key,product.getId(),specId,product.getTitle(),product.getCurrency(),quantity,getUnitPrice(product,specId,quantity));
    }

    private SettlementService.Quote quoteCart(List<CartItem> cart, String method) {
        return settlementService.quote(cart.stream().map(item -> source(item.getId().toString(),loadProduct(item.getProductId()),item.getSpecId(),item.getQuantity())).toList(),method);
    }

    private void checkQuote(Map<String,Object> request, SettlementService.Quote quote) {
        if (!quote.quoteToken().equals(optionalString(request,"quote_token")))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"价格、汇率或购物车内容已变化，请刷新结算金额后重新确认");
    }

    private void snapshot(Order order, SettlementService.Quote quote) {
        order.setSettlementCurrency("CNY"); order.setPaymentCurrency(quote.paymentCurrency()); order.setPaymentAmount(quote.paymentAmount());
    }

    private void snapshot(OrderItem item, SettlementService.Line line) {
        item.setSourceCurrency(line.sourceCurrency()); item.setSourceUnitPrice(line.sourceUnitPrice()); item.setExchangeRate(line.exchangeRate());
    }

    @Override
    @Transactional(timeout = 15)
    public CreatedOrder createDirectOrder(Map<String, Object> req, UUID userId, String clientIp, String sessionToken) {
        String device = optionalString(req, "device");
        String idempotencyKey = optionalString(req, "idempotency_key");
        inventoryLifecycle.lockRequest(idempotencyKey);
        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Order existingOrder = existing.get();
                // F13: 幂等归属校验 — 确保是同一用户/会话的请求，防止通过幂等键探测他人订单
                boolean sameOwner = (userId != null && userId.equals(existingOrder.getUserId()))
                        || (userId == null && existingOrder.getUserId() == null
                            && Objects.equals(sessionToken, existingOrder.getSessionToken()));
                if (sameOwner) {
                    return buildOrderResult(existingOrder, device);
                }
                // 不同用户/会话的相同幂等键 — 清除以避免唯一约束冲突，视为无幂等键的新订单
                idempotencyKey = null;
            }
        }

        UUID productId = requireUuid(req, "product_id");
        UUID specId = optionalUuid(req, "spec_id");
        int quantity = requireInt(req, "quantity");
        String email = optionalString(req, "email");

        // F4: 购买数量校验（读取后台配置，兜底 999）
        int maxQuantity = getMaxPurchasePerUser();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED, "购买数量无效，允许范围 1~" + maxQuantity,
                    Map.of("max", maxQuantity));
        }

        // F14: 提前提取 email，用于 pending 订单限制（email + IP 双维度防刷）
        checkPendingOrderLimits(userId, clientIp, email);
        String paymentMethod = optionalString(req, "payment_method");
        validatePaymentMethod(paymentMethod);

        Product product = productRepository.findById(productId)
                .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));

        // F18: 规格模式安全校验 — 防止通过伪造 spec_id 访问非当前模式的库存池或获取不同价格
        validateSpecConsistency(product, specId);

        // Stock check (advisory)
        long available = specId != null
                ? cardKeyRepository.countByProductIdAndSpecIdAndStatus(productId, specId, CardKeyStatus.AVAILABLE)
                : cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(productId, CardKeyStatus.AVAILABLE);
        if (available < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "库存不足",
                    Map.of("available", available));
        }

        var priceQuote = settlementService.quote(List.of(source(productId.toString(),product,specId,quantity)),paymentMethod);
        checkQuote(req,priceQuote);
        var priceLine = priceQuote.items().getFirst();
        BigDecimal unitPrice = priceLine.unitPrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常，请联系客服");
        }
        int expireMinutes = getConfigInt("order_expire_minutes", 15);

        Order order = new Order();
        order.setUserId(userId);
        order.setEmail(email);
        order.setTotalAmount(totalAmount);
        snapshot(order,priceQuote);
        order.setActualAmount(totalAmount);
        order.setStatus(OrderStatus.PENDING);
        order.setOrderType(OrderType.DIRECT);
        order.setPaymentMethod(paymentMethod);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(expireMinutes));
        order.setIdempotencyKey(idempotencyKey);
        order.setClientIp(clientIp);
        order.setSessionToken(sessionToken);
        orderRepository.save(order);

        String specName = null;
        if (specId != null) {
            ProductSpec spec = productSpecRepository.findById(specId).orElse(null);
            specName = spec != null ? spec.getName() : null;
        }

        OrderItem item = new OrderItem();
        item.setOrderId(order.getId());
        item.setProductId(productId);
        item.setSpecId(specId);
        item.setProductTitle(product.getTitle());
        item.setSpecName(specName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setSubtotal(totalAmount);
        snapshot(item,priceLine);
        orderItemRepository.save(item);

        inventoryLifecycle.reserve(order.getId());
        return buildOrderResult(order, device);
    }

    @Override
    @Transactional(timeout = 15)
    public CreatedOrder createCartOrder(Map<String, Object> req, UUID userId, String clientIp, String sessionToken) {
        String device = optionalString(req, "device");
        String idempotencyKey = optionalString(req, "idempotency_key");
        inventoryLifecycle.lockRequest(idempotencyKey);
        if (idempotencyKey != null) {
            Optional<Order> existing = orderRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                Order existingOrder = existing.get();
                boolean sameOwner = (userId != null && userId.equals(existingOrder.getUserId()))
                        || (userId == null && existingOrder.getUserId() == null
                            && Objects.equals(sessionToken, existingOrder.getSessionToken()));
                if (sameOwner) {
                    return buildOrderResult(existingOrder, device);
                }
                // 不同用户/会话的相同幂等键 — 清除以避免唯一约束冲突，视为无幂等键的新订单
                idempotencyKey = null;
            }
        }

        String email = optionalString(req, "email");
        checkPendingOrderLimits(userId, clientIp, email);
        String paymentMethod = optionalString(req, "payment_method");
        validatePaymentMethod(paymentMethod);

        List<CartItem> cartItems;
        if (userId != null) {
            cartItems = cartItemRepository.findByUserId(userId);
        } else if (sessionToken != null) {
            cartItems = cartItemRepository.findBySessionToken(sessionToken);
        } else {
            cartItems = List.of();
        }
        if (cartItems.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY, "购物车为空");
        }

        // 购物车每项数量校验（与直接下单统一上限）
        int maxQuantity = getMaxPurchasePerUser();
        for (CartItem ci : cartItems) {
            if (ci.getQuantity() < 1 || ci.getQuantity() > maxQuantity) {
                throw new BusinessException(ErrorCode.PURCHASE_LIMIT_EXCEEDED,
                        "购买数量无效，允许范围 1~" + maxQuantity,
                        Map.of("max", maxQuantity));
            }
        }

        int expireMinutes = getConfigInt("order_expire_minutes", 15);
        BigDecimal totalAmount = BigDecimal.ZERO;
        var priceQuote = quoteCart(cartItems,paymentMethod);
        checkQuote(req,priceQuote);

        Order order = new Order();
        order.setUserId(userId);
        order.setEmail(email);
        order.setStatus(OrderStatus.PENDING);
        order.setOrderType(OrderType.CART);
        snapshot(order,priceQuote);
        order.setPaymentMethod(paymentMethod);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(expireMinutes));
        order.setIdempotencyKey(idempotencyKey);
        order.setClientIp(clientIp);
        order.setSessionToken(sessionToken);
        orderRepository.save(order);

        for (CartItem ci : cartItems) {
            // F15: 防御性数量校验 — 购物车项数量必须为正整数，防止负数数量绕过价格计算
            if (ci.getQuantity() < 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "购物车包含无效数量，请刷新购物车后重试");
            }

            Product product = productRepository.findById(ci.getProductId())
                    .filter(p -> p.getIsDeleted() == 0 && p.isEnabled())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架"));

            // F18: 规格模式安全校验
            validateSpecConsistency(product, ci.getSpecId());

            // Advisory stock check (same pattern as createDirectOrder)
            long available = ci.getSpecId() != null
                    ? cardKeyRepository.countByProductIdAndSpecIdAndStatus(ci.getProductId(), ci.getSpecId(), CardKeyStatus.AVAILABLE)
                    : cardKeyRepository.countByProductIdAndSpecIdIsNullAndStatus(ci.getProductId(), CardKeyStatus.AVAILABLE);
            if (available < ci.getQuantity()) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                        "商品「" + product.getTitle() + "」库存不足",
                        Map.of("available", available, "title", product.getTitle()));
            }

            var priceLine = priceQuote.items().stream().filter(line -> line.key().equals(ci.getId().toString())).findFirst().orElseThrow();
            BigDecimal unitPrice = priceLine.unitPrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(ci.getQuantity()));
            totalAmount = totalAmount.add(subtotal);

            String specName = null;
            if (ci.getSpecId() != null) {
                ProductSpec spec = productSpecRepository.findById(ci.getSpecId()).orElse(null);
                specName = spec != null ? spec.getName() : null;
            }

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(ci.getProductId());
            item.setSpecId(ci.getSpecId());
            item.setProductTitle(product.getTitle());
            item.setSpecName(specName);
            item.setQuantity(ci.getQuantity());
            item.setUnitPrice(unitPrice);
            item.setSubtotal(subtotal);
            snapshot(item,priceLine);
            orderItemRepository.save(item);
        }

        // F16: 订单金额必须为正数 — 防止负数商品价格叠加导致极低金额下单
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "订单金额异常，请联系客服");
        }

        order.setTotalAmount(totalAmount);
        order.setActualAmount(totalAmount);
        orderRepository.save(order);

        // Clear cart after order creation to prevent duplicate orders from same cart items
        for (CartItem ci : cartItems) {
            cartItemRepository.delete(ci);
        }

        inventoryLifecycle.reserve(order.getId());
        return buildOrderResult(order, device);
    }

    @Override
    public void markPaymentSetupFailed(UUID orderId) {
        // A timeout may have created a payable gateway order: retain until normal expiry.
        // Definitive failures can be explicitly closed by close(id,true).
        inventoryLifecycle.close(orderId, false);
        log.warn("Payment setup outcome unknown; reservation retained until order expiry: {}", orderId);

    }

    @Override
    public Map<String, Object> getOrderStatus(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        // Auto expire check
        if (order.getStatus() == OrderStatus.PENDING && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            try { inventoryLifecycle.close(order.getId(), false); }
            catch (com.simplecard.exception.BusinessException busy) {
                if (busy.getHttpStatus() != org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) throw busy;
            }
            order = orderRepository.findById(orderId).orElseThrow();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        result.put("status", order.getStatus().name());
        result.put("payment_processing", paymentEvents.existsByOrderIdAndProcessedFalse(orderId));
        result.put("payment_review_reason", order.getPaymentReviewReason());
        result.put("expires_at", order.getExpiresAt());
        // 返回服务端计算的剩余秒数，前端倒计时以此为准，不受客户端时钟影响
        if (order.getStatus() == OrderStatus.PENDING) {
            long remainingSeconds = Duration.between(LocalDateTime.now(), order.getExpiresAt()).getSeconds();
            result.put("remaining_seconds", Math.max(0, remainingSeconds));
        } else {
            result.put("remaining_seconds", 0);
        }
        if (order.getStatus() == OrderStatus.PENDING) {
            // 优先返回二维码 URL（PC），其次 H5 跳转链接（移动端）
            String effectiveUrl = order.getQrcodeUrl() != null ? order.getQrcodeUrl() : order.getPaymentUrl();
            if (effectiveUrl != null) {
                result.put("payment_url", effectiveUrl);
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> refreshOrderStatus(UUID orderId) {
        // In a real implementation, this would query the payment provider
        return getOrderStatus(orderId);
    }

    @Override
    @Scheduled(fixedRate = 60000)
    public void expireOrders() {
        List<Order> expired = orderRepository.findExpiredOrders(LocalDateTime.now());
        for (Order order : expired) {
            try { inventoryLifecycle.close(order.getId(), false); }
            catch (RuntimeException busy) { log.warn("Expiry deferred for order {} ({})",order.getId(),busy.getClass().getSimpleName()); continue; }
            log.info("Order expired: {}", order.getId());
        }
    }

    private BigDecimal getUnitPrice(Product product, UUID specId, int quantity) {
        BigDecimal basePrice = product.getBasePrice();
        if (specId != null) {
            // F1: 严格校验规格归属 — 防止用低价规格 ID 篡改高价商品的价格
            ProductSpec spec = productSpecRepository.findById(specId)
                    .filter(s -> s.getProductId().equals(product.getId()) && s.getIsDeleted() == 0)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SPEC_NOT_FOUND, "商品规格不存在或与商品不匹配"));
            basePrice = spec.getPrice();
        }

        if (product.isWholesaleEnabled()) {
            List<WholesaleRule> rules;
            if (specId != null) {
                rules = wholesaleRuleRepository.findByProductIdAndSpecIdOrderByMinQuantityAsc(product.getId(), specId);
            } else {
                rules = wholesaleRuleRepository.findByProductIdAndSpecIdIsNullOrderByMinQuantityAsc(product.getId());
            }
            // Find matching tier (highest minQuantity <= quantity)
            for (int i = rules.size() - 1; i >= 0; i--) {
                if (quantity >= rules.get(i).getMinQuantity()) {
                    return rules.get(i).getUnitPrice();
                }
            }
        }
        return basePrice;
    }

    private void checkPendingOrderLimits(UUID userId, String clientIp, String email) {
        // 账号/邮箱维度与 IP 维度分别取各自的配置项。
        // 旧实现三个维度共用 max_pending_orders_per_user，使 max_pending_orders_per_ip
        // 成为死配置 —— 它在 data.sql 有种子、在风控 API 白名单里可写、前端也有字段，
        // 管理员为放宽共享 IP（校园网、企业出口）而修改它却毫无作用。
        int maxPendingPerUser = getConfigInt("max_pending_orders_per_user", 5);
        int maxPendingPerIp = getConfigInt("max_pending_orders_per_ip", maxPendingPerUser);

        if (userId != null) {
            long pending = orderRepository.countByUserIdAndStatus(userId, OrderStatus.PENDING);
            if (pending >= maxPendingPerUser) {
                throw new BusinessException(ErrorCode.UNPAID_ORDER_EXISTS, "您有未支付的订单，请先完成支付或等待过期");
            }
        }
        if (clientIp != null) {
            long pending = orderRepository.countByClientIpAndStatus(clientIp, OrderStatus.PENDING);
            if (pending >= maxPendingPerIp) {
                throw new BusinessException(ErrorCode.UNPAID_ORDER_EXISTS, "您有未支付的订单，请先完成支付或等待过期");
            }
        }
        // F14: 邮箱维度 pending 订单限制 — 防止通过 IP 轮换绕过限制
        if (email != null && !email.isBlank()) {
            long pending = orderRepository.countByEmailAndStatus(email, OrderStatus.PENDING);
            if (pending >= maxPendingPerUser) {
                throw new BusinessException(ErrorCode.UNPAID_ORDER_EXISTS, "该邮箱有未支付的订单，请先完成支付或等待过期");
            }
        }
    }

    /**
     * 组装建单结果 — 只读数据库，不触碰支付网关。
     * 支付创建由 OrderController 在事务提交后发起，理由见 {@link OrderService.CreatedOrder}。
     */
    private CreatedOrder buildOrderResult(Order order, String device) {
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        return new CreatedOrder(
                order.getId(),
                order.getPaymentMethod(),
                order.getActualAmount(),
                device,
                toOrderDetail(order, items));
    }

    private Map<String, Object> toOrderDetail(Order o, List<OrderItem> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", o.getId());
        map.put("total_amount", o.getTotalAmount());
        map.put("actual_amount", o.getActualAmount());
        map.put("currency", "CNY");
        map.put("payment_currency",o.getPaymentCurrency());
        map.put("payment_amount",o.getPaymentAmount());
        map.put("status", o.getStatus().name());
            map.put("payment_review_reason", o.getPaymentReviewReason());
        map.put("order_type", o.getOrderType().name());
        map.put("payment_method", o.getPaymentMethod());
        map.put("created_at", o.getCreatedAt());
        map.put("email", o.getEmail());
        map.put("points_deducted", o.getPointsDeducted());
        map.put("points_discount", o.getPointsDiscount());
        map.put("expires_at", o.getExpiresAt());
        map.put("paid_at", o.getPaidAt());
        map.put("delivered_at", o.getDeliveredAt());
        map.put("items", items.stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id", i.getId());
            im.put("product_id", i.getProductId());
            im.put("product_title", i.getProductTitle());
            im.put("spec_name", i.getSpecName());
            im.put("quantity", i.getQuantity());
            im.put("unit_price", i.getUnitPrice());
            im.put("subtotal", i.getSubtotal());
            im.put("source_currency",i.getSourceCurrency());
            im.put("source_unit_price",i.getSourceUnitPrice());
            im.put("exchange_rate",i.getExchangeRate());
            return im;
        }).toList());
        return map;
    }

    /**
     * F18: 规格模式安全校验
     * - specEnabled=true 且有规格时，必须提供 spec_id（防止跨池分配卡密）
     * - specEnabled=false 时，不允许传 spec_id（防止绕过模式获取规格价格）
     */
    private void validateSpecConsistency(Product product, UUID specId) {
        if (product.isSpecEnabled()) {
            List<ProductSpec> activeSpecs = productSpecRepository
                    .findByProductIdAndIsDeletedOrderBySortOrderAsc(product.getId(), 0);
            if (!activeSpecs.isEmpty() && specId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品需要选择规格");
            }
        } else {
            if (specId != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "该商品不支持规格选择");
            }
        }
    }

    /**
     * 支付渠道前置校验 — 在订单落库前确认渠道存在且启用，防止伪造不存在的支付方式绕过后续校验
     */
    private void validatePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "支付方式不能为空");
        }
        paymentChannelRepository.findByChannelCodeAndIsDeleted(paymentMethod, 0)
                .filter(PaymentChannel::isEnabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));
    }

    /** 每用户单次最大购买数量，后台可配，兜底 999（配置异常或 ≤ 0 时回退） */
    private int getMaxPurchasePerUser() {
        int val = getConfigInt("max_purchase_per_user", 999);
        return (val > 0 && val <= 999) ? val : 999;
    }

    // ── 请求参数安全提取 ──
    //
    // POST /orders 与 /orders/from-cart 都是 permitAll，直接对未校验的 Map 做强制转型/拆箱时，
    // 缺字段会抛 NullPointerException、类型不符会抛 ClassCastException，两者都以 500 返回并在
    // 日志里堆栈刷屏；正常用户漏填字段也只会看到"服务器错误"而非可理解的参数提示。
    // 下列辅助方法统一转成 400 + 明确原因。

    private static String optionalString(Map<String, Object> req, String field) {
        Object v = req.get(field);
        return v != null ? v.toString() : null;
    }

    private static UUID requireUuid(Map<String, Object> req, String field) {
        Object v = req.get(field);
        if (v == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少必填参数：" + field);
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数 " + field + " 不是合法的 ID");
        }
    }

    private static UUID optionalUuid(Map<String, Object> req, String field) {
        return req.get(field) != null ? requireUuid(req, field) : null;
    }

    private static int requireInt(Map<String, Object> req, String field) {
        Object v = req.get(field);
        if (v == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少必填参数：" + field);
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            // 前端以字符串形式传数字时同样接受，避免因序列化差异产生 500
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "参数 " + field + " 必须是整数");
        }
    }

    private int getConfigInt(String key, int defaultValue) {
        return siteConfigRepository.findByConfigKey(key)
                .map(c -> {
                    try { return Integer.parseInt(c.getConfigValue()); }
                    catch (Exception e) { return defaultValue; }
                }).orElse(defaultValue);
    }
}
