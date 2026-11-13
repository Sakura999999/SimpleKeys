package com.simplecard.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.constant.ErrorCode;
import com.simplecard.entity.Order;
import com.simplecard.entity.OrderItem;
import com.simplecard.entity.PaymentChannel;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.OrderItemRepository;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.PaymentChannelRepository;
import com.simplecard.service.BepusdtService;
import com.simplecard.service.BepusdtService.BepusdtConfig;
import com.simplecard.service.BepusdtService.BepusdtPaymentResult;
import com.simplecard.service.EpayService;
import com.simplecard.service.EpayService.ChannelConfig;
import com.simplecard.service.EpayService.EpayResult;
import com.simplecard.service.OrderAccessGuard;
import com.simplecard.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    /** channel_code → 易支付 type 映射（仅 provider_type=epay 时使用） */
    private static final Map<String, String> EPAY_TYPE_MAP = Map.of(
            "alipay", "alipay",
            "wechat", "wxpay"
    );

    private final PaymentChannelRepository paymentChannelRepository;
    private final OrderRepository orderRepository;
    private final com.simplecard.service.InventoryLifecycleService inventoryLifecycle;
    private final OrderItemRepository orderItemRepository;
    private final EpayService epayService;
    private final BepusdtService bepusdtService;
    private final ObjectMapper objectMapper;
    private final OrderAccessGuard orderAccessGuard;

    @Override
    public Map<String, Object> createPayment(UUID orderId, String paymentMethod, BigDecimal amount) {
        return createPayment(orderId, paymentMethod, amount, null);
    }

    @Override
    public Map<String, Object> createPayment(UUID orderId, String paymentMethod, BigDecimal amount, String device) {
        inventoryLifecycle.close(orderId, false);
        inventoryLifecycle.reserve(orderId);
        try { return createPaymentRequest(orderId, paymentMethod, amount, device); }
        catch (com.simplecard.exception.PaymentRejectedException rejected) {
            inventoryLifecycle.close(orderId, true);
            throw rejected;
        }
    }

    private Map<String,Object> createPaymentRequest(UUID orderId, String paymentMethod, BigDecimal amount, String device) {
        // 1. 查找渠道并验证已启用
        PaymentChannel channel = paymentChannelRepository.findByChannelCodeAndIsDeleted(paymentMethod, 0)
                .filter(PaymentChannel::isEnabled)
                .orElseThrow(() -> new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE, "支付渠道不可用"));

        // 2. 查找订单
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        requirePending(order);
        // 3. 幂等：已有支付URL直接返回（paymentUrl 或 qrcodeUrl 任一存在即可）
        if ((order.getPaymentUrl() != null && !order.getPaymentUrl().isEmpty())
                || (order.getQrcodeUrl() != null && !order.getQrcodeUrl().isEmpty())) {
            log.info("Returning cached payment URL for order: {}", orderId);
            return buildResult(order);
        }

        // 4. 按 providerType 路由到不同的支付实现
        String providerType = channel.getProviderType();
        switch (providerType) {
            case "epay" -> createEpayPayment(channel, order, paymentMethod, order.getActualAmount(), device);
            case "native_alipay" -> throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE, "原生支付宝支付尚未实现，请使用易支付渠道");
            case "native_wxpay" -> throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE, "原生微信支付尚未实现，请使用易支付渠道");
            case "usdt" -> createBepusdtPayment(channel, order, order.getPaymentAmount() != null ? order.getPaymentAmount() : order.getActualAmount());
            default -> throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE, "不支持的支付提供商类型: " + providerType);
        }

        Order current = orderRepository.findById(orderId).orElseThrow();
        requirePending(current);
        return buildResult(current);
    }

    /**
     * BEpusdt USDT 下单流程
     */
    private void createBepusdtPayment(PaymentChannel channel, Order order, BigDecimal amount) {
        BepusdtConfig config = buildBepusdtConfig(channel);
        if (order.getPaymentCurrency() != null) {
            config = new BepusdtConfig(config.apiUrl(),config.apiToken(),config.notifyUrl(),config.redirectUrl(),config.tradeType(),order.getPaymentCurrency(),config.timeout(),config.fixedRate());
        }
        String productName = buildProductName(order.getId());

        BepusdtPaymentResult result = bepusdtService.createPayment(
                config, order.getId().toString(), amount, productName);

        order.setPaymentUrl(result.paymentUrl());
        order.setUsdtWalletAddress(result.walletAddress());
        order.setUsdtCryptoAmount(result.cryptoAmount());
        order.setUsdtTradeId(result.tradeId());
        order.setUsdtChain(channel.getChannelCode());
        inventoryLifecycle.storePaymentDetails(order);
    }

    /**
     * 从渠道的 config_data JSON 构建 BepusdtConfig。
     */
    public BepusdtConfig buildBepusdtConfig(PaymentChannel channel) {
        Map<String, String> cfg = parseConfigData(channel.getConfigData());

        String apiUrl = requireConfig(cfg, "api_url", channel.getChannelCode());
        String apiToken = requireConfig(cfg, "api_token", channel.getChannelCode());
        String notifyUrl = requireConfig(cfg, "notify_url", channel.getChannelCode());
        String redirectUrl = cfg.getOrDefault("redirect_url", "");
        String tradeType = cfg.getOrDefault("trade_type", "usdt.trc20");
        String fiat = cfg.getOrDefault("fiat", "CNY");
        int timeout = Integer.parseInt(cfg.getOrDefault("timeout", "900"));
        String fixedRate = cfg.getOrDefault("fixed_rate", "");

        return new BepusdtConfig(apiUrl, apiToken, notifyUrl, redirectUrl,
                tradeType, fiat, timeout, fixedRate);
    }

    /**
     * 易支付下单流程
     */
    private void createEpayPayment(PaymentChannel channel, Order order, String paymentMethod, BigDecimal amount, String device) {
        String epayType = EPAY_TYPE_MAP.get(paymentMethod.toLowerCase());
        if (epayType == null) {
            throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE, "该渠道不支持易支付");
        }

        ChannelConfig config = buildChannelConfig(channel);
        String productName = buildProductName(order.getId());

        EpayResult epayResult = epayService.createPayment(
                config,
                order.getId().toString(),
                epayType,
                productName,
                amount,
                order.getClientIp(),
                device
        );

        // 分别存储：payUrl 是 H5 跳转链接，qrcodeUrl 是二维码 URL
        order.setPaymentUrl(epayResult.payUrl());
        order.setQrcodeUrl(epayResult.qrcodeUrl());
        order.setEpayTradeNo(epayResult.tradeNo());
        inventoryLifecycle.storePaymentDetails(order);
    }

    /**
     * 从渠道的 config_data JSON 构建 EpayService.ChannelConfig。
     * 所有必填字段均从数据库渠道配置读取，缺失则抛出异常。
     */
    public ChannelConfig buildChannelConfig(PaymentChannel channel) {
        Map<String, String> cfg = parseConfigData(channel.getConfigData());

        String pid = requireConfig(cfg, "pid", channel.getChannelCode());
        String key = requireConfig(cfg, "key", channel.getChannelCode());
        String apiUrl = requireConfig(cfg, "api_url", channel.getChannelCode());
        String notifyUrl = requireConfig(cfg, "notify_url", channel.getChannelCode());
        String returnUrl = requireConfig(cfg, "return_url", channel.getChannelCode());

        return new ChannelConfig(pid, key, apiUrl, notifyUrl, returnUrl);
    }

    private Map<String, Object> buildResult(Order order) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", order.getId());
        // payment_url: 兼容旧逻辑，优先返回 qrcodeUrl（PC 二维码），fallback 到 paymentUrl（H5 跳转）
        String effectiveUrl = order.getQrcodeUrl() != null ? order.getQrcodeUrl() : order.getPaymentUrl();
        result.put("payment_url", effectiveUrl);
        result.put("qrcode_url", order.getQrcodeUrl());
        result.put("pay_url", order.getPaymentUrl());
        result.put("expires_at", order.getExpiresAt());
        result.put("settlement_currency", "CNY");
        result.put("settlement_amount",order.getActualAmount());
        result.put("payment_currency",order.getPaymentCurrency());
        result.put("payment_amount",order.getPaymentAmount());

        // USDT 支付额外字段
        if (order.getUsdtWalletAddress() != null) {
            result.put("wallet_address", order.getUsdtWalletAddress());
            result.put("crypto_amount", order.getUsdtCryptoAmount());
            result.put("chain", order.getUsdtChain());
        }
        return result;
    }

    private String buildProductName(UUID orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
        if (items.isEmpty()) return "简易发卡订单";
        String firstName = items.getFirst().getProductTitle();
        if (items.size() == 1) return firstName;
        return firstName + " 等" + items.size() + "件商品";
    }

    private Map<String, String> parseConfigData(String configData) {
        if (configData == null || configData.isBlank()) return Map.of();
        try {
            Map<String, Object> raw = objectMapper.readValue(configData, new TypeReference<>() {});
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), entry.getValue().toString());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse channel config_data: {}", e.getMessage());
            return Map.of();
        }
    }

    /** repay 最小间隔（秒），防止频繁调用冲击支付网关 */
    private static final int REPAY_COOLDOWN_SECONDS = 10;

    @Override
    // 刻意不加 @Transactional：本方法末尾会调用支付网关（最坏约 62 秒），
    // 包在事务里会长时间占用 Hikari 连接并耗尽连接池。方法内的写入各自独立，
    // 由 Spring Data 的隐式事务保证单条语句的原子性即可。
    public Map<String, Object> repay(UUID orderId, String device, String callerSessionToken) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));

        // F9: 归属校验 — 交由 OrderAccessGuard 统一执行。
        // 旧实现的条件含 requestUserId != null，未登录请求会整段跳过校验，
        // 而 /orders/*/repay 为 permitAll，任何人都能反复重建他人订单的支付链接。
        orderAccessGuard.assertAccess(order, callerSessionToken);

        if (order.getStatus() != com.simplecard.constant.OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单状态不允许重新支付");
        }

        if (order.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            inventoryLifecycle.close(orderId, false);
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期");
        }

        // 频率限制：距上次更新不足 REPAY_COOLDOWN_SECONDS 秒则拒绝
        if (order.getUpdatedAt() != null
                && order.getUpdatedAt().plusSeconds(REPAY_COOLDOWN_SECONDS).isAfter(java.time.LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }

        inventoryLifecycle.clearPaymentDetails(orderId);

        // 重新创建支付
        return createPayment(order.getId(), order.getPaymentMethod(), order.getActualAmount(), device);
    }

    private static void requirePending(Order order) {
        if (order.getStatus() != com.simplecard.constant.OrderStatus.PENDING || order.getExpiresAt().isBefore(java.time.LocalDateTime.now()))
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期或已付款，请查询订单");
    }

    private static String requireConfig(Map<String, String> cfg, String field, String channelCode) {
        String value = cfg.get(field);
        if (value == null || value.isBlank()) {
            throw new com.simplecard.exception.PaymentRejectedException(ErrorCode.CHANNEL_UNAVAILABLE,
                    "支付渠道 [" + channelCode + "] 缺少必填配置项: " + field + "，请在后台「支付渠道管理」中完善配置");
        }
        return value;
    }
}
