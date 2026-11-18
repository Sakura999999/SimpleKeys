package com.simplecard.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.Order;
import com.simplecard.entity.PaymentChannel;
import com.simplecard.entity.WebhookEvent;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.PaymentChannelRepository;
import com.simplecard.repository.WebhookEventRepository;
import com.simplecard.service.BepusdtService;
import com.simplecard.service.EpayService;
import com.simplecard.service.TxidVerifyService;
import com.simplecard.service.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookServiceImpl implements WebhookService {

    private final WebhookEventRepository webhookEventRepository;
    private final OrderRepository orderRepository;
    private final com.simplecard.service.PaymentInboxService paymentInbox;
    private final com.simplecard.service.InventoryLifecycleService inventoryLifecycle;
    private final PaymentChannelRepository paymentChannelRepository;
    private final EpayService epayService;
    private final BepusdtService bepusdtService;
    private final ObjectMapper objectMapper;
    private final PaymentServiceImpl paymentService;
    private final TxidVerifyService txidVerifyService;
    private final com.simplecard.service.PaymentAttemptService paymentAttempts;

    @Override
    public String processEpayCallback(Map<String, String> params) {
        String tradeNo = params.get("trade_no");
        String outTradeNo = params.get("out_trade_no");
        String tradeStatus = params.get("trade_status");
        String money = params.get("money");
        String sign = params.get("sign");

        // Step 1: Parse order ID
        UUID orderId;
        try {
            orderId = UUID.fromString(outTradeNo == null ? "" : outTradeNo);
        } catch (IllegalArgumentException e) {
            log.debug("Epay callback rejected: invalid order ID");
            return "FAIL";
        }

        Order order = orderRepository.findById(orderId).orElse(null);
        EpayService.ChannelConfig channelConfig = order == null ? null : resolveChannelConfig(order);
        if (channelConfig == null
                || !channelConfig.pid().equals(params.get("pid"))
                || !java.util.Objects.equals(paymentService.epayType(order.getPaymentMethod()), params.get("type"))
                || params.get("type") == null
                || !"MD5".equalsIgnoreCase(params.get("sign_type"))
                || (params.containsKey("currency") && !"CNY".equalsIgnoreCase(params.get("currency")))
                || tradeNo == null || tradeNo.isBlank()) {
            return "FAIL";
        }
        if (!epayService.verifySign(channelConfig.key(), params, sign)) {
            log.warn("Epay callback signature verification failed: order={}", orderId);
            return "FAIL";
        }

        // Step 4: Check trade status（非成功状态不写入幂等表，避免阻塞后续成功回调）
        if (!"TRADE_SUCCESS".equals(tradeStatus)) {
            log.debug("Epay callback skipped: order={} status was not successful", orderId);
            return "SUCCESS";
        }

        // Step 5: Process payment
        String eventId = "epay_" + tradeNo;
        WebhookEvent event = webhookEventRepository.findByEventId(eventId).orElseGet(WebhookEvent::new);
        event.setEventId(eventId);
        event.setChannelCode("epay");
        event.setOrderId(orderId);
        event.setPayload("Signed callback received");

        // Step 6: Verify amount matches (money 必须存在且与订单金额一致)
        if (money == null || money.isBlank()) {
            log.warn("Epay callback missing amount: order={}", orderId);
            event.setProcessResult("MISSING_AMOUNT");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        BigDecimal callbackAmount;
        try {
            callbackAmount = new BigDecimal(money);
        } catch (NumberFormatException e) {
            log.warn("Epay callback invalid amount: order={}", orderId);
            event.setProcessResult("INVALID_AMOUNT_FORMAT");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        if (order.getActualAmount().compareTo(callbackAmount) != 0) {
            log.warn("Epay callback amount mismatch: order={}", orderId);
            event.setProcessResult("AMOUNT_MISMATCH");
            webhookEventRepository.save(event);
            return "FAIL";
        }

        // Step 7: 服务端主动查询网关订单状态（防止伪造回调）
        EpayService.OrderQueryResult queryResult = epayService.queryOrder(channelConfig, outTradeNo);
        if (queryResult == null) {
            // 网络/网关故障 — 不写入幂等表，返回 FAIL 触发网关重试
            log.warn("Epay callback deferred: order={} lookup unavailable", orderId);
            return "FAIL";
        }
        // 查询 API 的 status 字段格式可能为 "TRADE_SUCCESS" 或 "1"（已支付），兼容两种
        if (!isQueryStatusPaid(queryResult.tradeStatus())) {
            log.warn("Epay callback lookup did not confirm payment: order={}", orderId);
            event.setProcessResult("QUERY_STATUS_MISMATCH");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        if (queryResult.tradeNo() == null || !queryResult.tradeNo().equals(tradeNo)
                || (order.getEpayTradeNo() != null && !order.getEpayTradeNo().equals(tradeNo))) {
            event.setProcessResult("QUERY_TRADE_MISMATCH");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        try {
            if (queryResult.money() == null || order.getActualAmount().compareTo(new BigDecimal(queryResult.money())) != 0) {
                event.setProcessResult("QUERY_AMOUNT_MISMATCH");
                webhookEventRepository.save(event);
                return "FAIL";
            }
        } catch (NumberFormatException e) {
            event.setProcessResult("QUERY_AMOUNT_MISMATCH");
            webhookEventRepository.save(event);
            return "FAIL";
        }
        log.info("Epay callback verified: order={}", orderId);
        PaymentReconciliationService.acceptEpay(paymentInbox, order, tradeNo);
        // Receipt has committed. Business processing may still be pending Redis recovery.

        return "SUCCESS";
    }

    @Override
    public String processBepusdtCallback(Map<String, Object> params) {
        // BEpusdt 回调 JSON 含非 String 类型（amount: float64, status: int），
        // 转为 Map<String, String> 用于签名验证（Object.toString() 与 Go 的 fmt.Sprintf("%v", v) 输出一致）
        Map<String, String> signParams = new LinkedHashMap<>();
        for (var entry : params.entrySet()) {
            if (entry.getValue() != null) {
                signParams.put(entry.getKey(), entry.getValue().toString());
            }
        }

        String tradeId = signParams.get("trade_id");
        String orderId = signParams.get("order_id");
        String status = signParams.get("status");
        String blockTxId = signParams.get("block_transaction_id");
        String signature = signParams.get("signature");

        // 1. 幂等检查
        String eventId = "bepusdt_" + tradeId;
        // 2. 解析订单
        UUID orderUuid;
        try {
            orderUuid = UUID.fromString(orderId == null ? "" : orderId);
        } catch (IllegalArgumentException e) {
            log.debug("BEpusdt callback rejected: invalid order ID");
            return "fail";
        }

        Order order = orderRepository.findById(orderUuid).orElse(null);
        if (order == null) {
            // F8: 订单未找到时不写入幂等表且返回 fail — 触发 BEpusdt 重试（可能是时序问题：回调先于订单落库）
            log.warn("BEpusdt callback order not found: order={}", orderUuid);
            return "fail";
        }

        // 3. 验签（apiToken 为空则拒绝，防止跳过签名验证）
        String apiToken = resolveBepusdtApiToken(order);
        if (apiToken == null) {
            log.warn("BEpusdt callback configuration unavailable: order={}", orderUuid);
            return "fail";
        }
        if (!bepusdtService.verifySign(apiToken, signParams, signature)) {
            log.warn("BEpusdt callback signature verification failed: order={}", orderUuid);
            // F3: 签名失败不写入幂等表 — 否则攻击者可伪造回调占用 eventId，阻塞后续真实回调
            return "fail";
        }

        // 4. 状态检查（只处理 status=2 即支付成功）
        // 注意：非成功状态不写入幂等表，否则后续 status=2 回调会被误拦截
        if (!"2".equals(status)) {
            log.debug("BEpusdt callback skipped: order={} status was not successful", orderUuid);
            return "ok";
        }

        // 5. 金额校验（actual_amount 和 usdtCryptoAmount 必须都存在且一致）
        if (tradeId == null || !tradeId.equals(order.getUsdtTradeId())) return "fail";
        String actualAmount = signParams.get("actual_amount");
        if (actualAmount == null || actualAmount.isBlank() || order.getUsdtCryptoAmount() == null) {
            log.warn("BEpusdt callback missing amount: order={}", orderUuid);
            return "fail";
        }
        BigDecimal bepCallbackAmount;
        BigDecimal bepOrderAmount;
        try {
            bepCallbackAmount = new BigDecimal(actualAmount);
            bepOrderAmount = new BigDecimal(order.getUsdtCryptoAmount());
        } catch (NumberFormatException e) {
            log.warn("BEpusdt callback invalid amount: order={}", orderUuid);
            saveWebhookEvent(eventId, "usdt", orderUuid, "INVALID_AMOUNT_FORMAT");
            return "ok";
        }
        if (bepCallbackAmount.compareTo(bepOrderAmount) != 0) {
            log.warn("BEpusdt callback amount mismatch: order={}", orderUuid);
            saveWebhookEvent(eventId, "usdt", orderUuid, "AMOUNT_MISMATCH");
            return "ok";
        }

        // 6. 链上验证 block_transaction_id（防止伪造回调 — 与 Epay 服务端查询网关等效）
        if (blockTxId == null || blockTxId.isBlank() || blockTxId.equals(tradeId)) {
            // status=2 但 block_transaction_id 不是真实链上哈希（等于 tradeId 或为空）
            // 不写入幂等表，返回 fail 触发 BEpusdt 重试（等待链上确认后重新回调）
            log.warn("BEpusdt callback missing chain transaction: order={}", orderUuid);
            return "fail";
        }

        String chain = order.getUsdtChain() != null ? order.getUsdtChain() : order.getPaymentMethod();
        blockTxId = PaymentReconciliationService.normalizeUsdtHash(chain, blockTxId);
        if (blockTxId == null) return "fail";
        TxidVerifyService.ChainVerifyResult chainResult =
                txidVerifyService.verifyForWebhook(chain, blockTxId, order.getUsdtWalletAddress(), order.getUsdtCryptoAmount(), order.getCreatedAt());

        if (chainResult == null) {
            paymentAttempts.rememberSignedTransfer(order.getId(), blockTxId);
            log.warn("BEpusdt callback deferred: order={} chain verification unavailable", orderUuid);
            return "fail";
        }
        if (!chainResult.verified()) {
            // 链上验证失败（交易不存在/未确认/地址不匹配/非USDT/金额不匹配）— 写入幂等表拒绝
            log.warn("BEpusdt callback failed chain verification: order={}", orderUuid);
            saveWebhookEvent(eventId, "usdt", orderUuid, "ONCHAIN_VERIFY_FAILED");
            return "ok";
        }
        log.info("BEpusdt callback verified: order={}", orderUuid);

        // 7. TXID 唯一性前置检查（防止同一链上交易被关联到多个订单）
        if (orderRepository.existsByUsdtTxIdIgnoreCaseAndIdNot(blockTxId, order.getId())) {
            log.warn("BEpusdt callback TXID already assigned: order={}", order.getId());
            saveWebhookEvent(eventId, "usdt", orderUuid, "TXID_ALREADY_USED");
            return "ok";
        }


        PaymentReconciliationService.acceptUsdt(paymentInbox, order, chain, blockTxId, chainResult);

        return "ok";
    }

    private void saveWebhookEvent(String eventId, String channelCode, UUID orderId, String processResult) {
        WebhookEvent event = webhookEventRepository.findByEventId(eventId).orElseGet(WebhookEvent::new);
        event.setEventId(eventId);
        event.setChannelCode(channelCode);
        event.setOrderId(orderId);
        event.setPayload("Signed callback received");
        event.setProcessResult(processResult);
        webhookEventRepository.save(event);
    }

    /**
     * 从已有 Order 对象查找渠道 config_data 中的 BEpusdt API Token。
     */
    private String resolveBepusdtApiToken(Order order) {
        if (order.getPaymentMethod() != null) {
            PaymentChannel channel = paymentChannelRepository
                    .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                    .orElse(null);
            if (channel != null && "usdt".equals(channel.getProviderType()) && channel.getConfigData() != null) {
                try {
                    Map<String, Object> cfg = objectMapper.readValue(
                            channel.getConfigData(), new TypeReference<>() {});
                    var attempt = paymentAttempts.find(order.getId()).orElse(null);
                    if (attempt != null && !com.simplecard.service.PaymentAttemptService.matches(
                            attempt, channel, java.util.Objects.toString(cfg.get("api_url"), null), null)) return null;
                    Object token = cfg.get("api_token");
                    if (token != null && !token.toString().isBlank()) {
                        return token.toString();
                    }
                } catch (Exception e) {
                    log.warn("Failed to read BEpusdt channel configuration: order={}, error={}", order.getId(), e.getClass().getSimpleName());
                }
            }
        }
        log.warn("Cannot resolve BEpusdt API token for order {}", order.getId());
        return null;
    }

    private boolean isQueryStatusPaid(String status) {
        return "TRADE_SUCCESS".equals(status) || "1".equals(status);
    }

    /**
     * 从订单关联的支付渠道解析完整的 ChannelConfig（pid/key/apiUrl/notifyUrl/returnUrl）。
     * 用于 webhook 回调后发起服务端主动查询。配置不完整时拒绝回调。
     */
    private EpayService.ChannelConfig resolveChannelConfig(Order order) {
        if (order.getPaymentMethod() == null) return null;
        PaymentChannel channel = paymentChannelRepository
                .findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0)
                .orElse(null);
        if (channel == null || !"epay".equals(channel.getProviderType())) return null;
        try {
            var config = paymentService.buildChannelConfig(channel);
            var attempt = paymentAttempts.find(order.getId()).orElse(null);
            return attempt == null || com.simplecard.service.PaymentAttemptService.matches(
                    attempt, channel, config.apiUrl(), config.pid()) ? config : null;
        } catch (Exception e) {
            log.warn("Failed to read Epay channel configuration: order={}, error={}", order.getId(), e.getClass().getSimpleName());
            return null;
        }
    }
}
