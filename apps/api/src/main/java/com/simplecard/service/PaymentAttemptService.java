package com.simplecard.service;

import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.Order;
import com.simplecard.entity.PaymentAttempt;
import com.simplecard.entity.PaymentChannel;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentAttemptService {
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;

    public Optional<PaymentAttempt> find(UUID orderId) {
        return attempts.findByOrderId(orderId);
    }

    public void rememberSignedTransfer(UUID orderId, String txid) {
        attempts.rememberSignedTransfer(orderId, txid);
    }

    @Transactional
    public boolean start(UUID orderId, PaymentChannel channel, String gatewayUrl, String merchantId,
                         BigDecimal amount, String currency) {
        Order order = lockOrder(orderId);
        LocalDateTime now = LocalDateTime.now();
        if (order.getStatus() != OrderStatus.PENDING || !order.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.ORDER_EXPIRED, "订单已过期或已付款，请查询订单");
        }
        if (attempts.findByOrderId(orderId).isPresent() || hasGatewayDetails(order)) return false;
        PaymentAttempt attempt = snapshot(order, channel, gatewayUrl, merchantId, amount, currency);
        attempt.setState(PaymentAttempt.State.CREATING);
        attempt.setNextCheckAt(now.plusMinutes(2));
        attempts.save(attempt);
        return true;
    }

    @Transactional
    public void rememberExisting(UUID orderId, PaymentChannel channel, String gatewayUrl, String merchantId,
                                 BigDecimal amount, String currency) {
        Order order = lockOrder(orderId);
        if (!hasGatewayDetails(order) || attempts.findByOrderId(orderId).isPresent()) return;
        PaymentAttempt attempt = snapshot(order, channel, gatewayUrl, merchantId, amount, currency);
        attempt.setGatewayTradeId("epay".equals(channel.getProviderType()) ? order.getEpayTradeNo() : order.getUsdtTradeId());
        attempt.setState(PaymentAttempt.State.READY);
        attempt.setNextCheckAt(LocalDateTime.now());
        attempts.save(attempt);
    }

    @Transactional
    public void creationFailed(UUID orderId, boolean rejected) {
        lockOrder(orderId);
        PaymentAttempt attempt = attempts.findByOrderId(orderId).orElseThrow();
        if (attempt.getState() != PaymentAttempt.State.CREATING) return;
        attempt.setState(rejected ? PaymentAttempt.State.REJECTED : PaymentAttempt.State.UNKNOWN);
        attempt.setLastCheckResult(rejected ? "CREATE_REJECTED" : "CREATE_OUTCOME_UNKNOWN");
        attempt.setNextCheckAt(rejected ? null : LocalDateTime.now().plusSeconds(30));
    }

    public static boolean matches(PaymentAttempt attempt, PaymentChannel channel, String gatewayUrl, String merchantId) {
        return Objects.equals(attempt.getChannelId(), channel.getId())
                && attempt.getProvider().equals(channel.getProviderType())
                && attempt.getGatewayUrl().equals(gatewayUrl)
                && Objects.equals(attempt.getMerchantId(), merchantId);
    }

    private PaymentAttempt snapshot(Order order, PaymentChannel channel, String gatewayUrl, String merchantId,
                                    BigDecimal amount, String currency) {
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setOrderId(order.getId());
        attempt.setChannelId(channel.getId());
        attempt.setProvider(channel.getProviderType());
        attempt.setGatewayUrl(gatewayUrl);
        attempt.setMerchantId(merchantId);
        attempt.setAmount(amount);
        attempt.setCurrency(currency);
        attempt.setExpiresAt(order.getExpiresAt());
        return attempt;
    }

    private Order lockOrder(UUID orderId) {
        return orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
    }

    private static boolean hasGatewayDetails(Order order) {
        return order.getPaymentUrl() != null || order.getQrcodeUrl() != null
                || order.getEpayTradeNo() != null || order.getUsdtTradeId() != null;
    }
}
