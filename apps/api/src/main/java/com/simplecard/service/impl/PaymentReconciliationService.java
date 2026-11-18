package com.simplecard.service.impl;

import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.entity.Order;
import com.simplecard.entity.PaymentAttempt;
import com.simplecard.entity.PaymentChannel;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.PaymentAttemptRepository;
import com.simplecard.repository.PaymentChannelRepository;
import com.simplecard.repository.VerifiedPaymentEventRepository;
import com.simplecard.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {
    private static final int WINDOW_HOURS = 24;
    private static final PageRequest BATCH = PageRequest.of(0, 5);
    private static final List<OrderStatus> UNSETTLED = List.of(OrderStatus.PENDING, OrderStatus.EXPIRED, OrderStatus.CANCELLED);

    private final PaymentAttemptRepository attempts;
    private final PaymentAttemptService attemptService;
    private final OrderRepository orders;
    private final PaymentChannelRepository channels;
    private final VerifiedPaymentEventRepository receipts;
    private final PaymentServiceImpl payments;
    private final EpayService epay;
    private final BepusdtService bepusdt;
    private final TxidVerifyService chain;
    private final PaymentInboxService inbox;
    @Value("${payment-reconciliation.enabled:true}")
    private boolean enabled;

    public void refresh(Order order) {
        reconcile(order, attemptService.find(order.getId()).orElse(null));
    }

    boolean isSetupPending(UUID orderId) {
        return attemptService.find(orderId).map(attempt -> attempt.getNextCheckAt() != null
                && (attempt.getState() == PaymentAttempt.State.CREATING || attempt.getState() == PaymentAttempt.State.UNKNOWN))
                .orElse(false);
    }

    @Scheduled(scheduler = "paymentReconciliationScheduler",
            initialDelayString = "${payment-reconciliation.initial-delay-ms:120000}",
            fixedDelayString = "${payment-reconciliation.poll-ms:15000}")
    public void checkDuePayments() {
        if (!enabled) return;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (UUID id : attempts.findDue(now, BATCH)) check(id, LocalDateTime.now().withNano(0));
        for (Order order : orders.findLegacyPayments(now.minusHours(WINDOW_HOURS), UNSETTLED, BATCH)) {
            try {
                importLegacy(order, channel(order));
            } catch (RuntimeException failure) {
                log.warn("Legacy payment could not be scheduled: order={}, cause={}", order.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void check(UUID id, LocalDateTime now) {
        LocalDateTime lease = now.plusMinutes(5);
        if (attempts.claim(id, now, lease) == 0) return;
        PaymentAttempt attempt = attempts.findById(id).orElseThrow();
        PaymentAttempt.State state = attempt.getState() == PaymentAttempt.State.CREATING
                ? PaymentAttempt.State.UNKNOWN : attempt.getState();
        try {
            if (state == PaymentAttempt.State.REJECTED || state == PaymentAttempt.State.VERIFIED) {
                attempts.completeCheck(id, lease, null, state, 0, "TERMINAL");
                return;
            }
            Order order = orders.findById(attempt.getOrderId()).orElseThrow();
            if (reconcile(order, attempt)) {
                attempts.completeCheck(id, lease, null, PaymentAttempt.State.VERIFIED, 0, "VERIFIED");
                return;
            }
            LocalDateTime next = nextCheck(attempt, 120);
            String result = PaymentAttempt.PAID_UNVERIFIED.equals(attempt.getLastCheckResult())
                    ? PaymentAttempt.PAID_UNVERIFIED
                    : next == null ? "RECONCILIATION_WINDOW_ENDED" : "WAITING_FOR_PAYMENT";
            attempts.completeCheck(id, lease, next, state, 0, result);
        } catch (RuntimeException unavailable) {
            int failures = Math.min(attempt.getCheckFailures() + 1, 10);
            LocalDateTime next = nextCheck(attempt, Math.min(900, 30L << Math.min(failures, 5)));
            String result = unavailable instanceof PaidUnverified || PaymentAttempt.PAID_UNVERIFIED.equals(attempt.getLastCheckResult())
                    ? PaymentAttempt.PAID_UNVERIFIED
                    : next == null ? "MANUAL_REVIEW_REQUIRED" : "PAYMENT_CHECK_DEFERRED";
            attempts.completeCheck(id, lease, next, state, failures, result);
            log.warn("Payment reconciliation deferred: order={}, count={}, cause={}",
                    attempt.getOrderId(), failures, unavailable.getClass().getSimpleName());
        }
    }

    private LocalDateTime nextCheck(PaymentAttempt attempt, long seconds) {
        LocalDateTime next = LocalDateTime.now().plusSeconds(seconds);
        return next.isAfter(attempt.getExpiresAt().plusHours(WINDOW_HOURS)) ? null : next;
    }

    private boolean reconcile(Order order, PaymentAttempt attempt) {
        if (order.getPaidAt() != null || order.getStatus() == OrderStatus.REFUNDED
                || receipts.existsByOrderIdAndProcessedFalse(order.getId())) return true;
        PaymentChannel channel = channel(order);
        return switch (channel.getProviderType()) {
            case "epay" -> reconcileEpay(order, channel, attempt);
            case "usdt" -> reconcileUsdt(order, channel, attempt);
            default -> false;
        };
    }

    private boolean reconcileEpay(Order order, PaymentChannel channel, PaymentAttempt attempt) {
        var config = payments.buildChannelConfig(channel);
        requireBinding(attempt, channel, config.apiUrl(), config.pid());
        var query = epay.queryOrder(config, order.getId().toString());
        if (query == null) throw unavailable();
        if ("0".equals(query.tradeStatus()) || "WAIT_BUYER_PAY".equals(query.tradeStatus())) return false;
        if (!"1".equals(query.tradeStatus()) && !"TRADE_SUCCESS".equals(query.tradeStatus())) throw unavailable();
        String trade = attempt != null && attempt.getGatewayTradeId() != null
                ? attempt.getGatewayTradeId() : order.getEpayTradeNo();
        boolean bound = trade != null ? trade.equals(query.tradeNo()) : attempt != null && query.merchantVerified();
        if (!bound || query.tradeNo() == null || query.tradeNo().isBlank()
                || !sameAmount(attempt == null ? order.getActualAmount() : attempt.getAmount(), query.money())) {
            throw new PaidUnverified();
        }
        // The V1 lookup provides no reliable payment timestamp. Closed orders need review.
        acceptEpay(inbox, order, query.tradeNo());
        return true;
    }

    static void acceptEpay(PaymentInboxService inbox, Order order, String tradeNo) {
        String review = order.getExpiresAt().isAfter(LocalDateTime.now()) ? null : "过期后查到付款，需核对实际付款时间";
        if (review == null) inbox.accept("epay_" + tradeNo, "epay", order.getId(), null);
        else inbox.accept("epay_" + tradeNo, "epay", order.getId(), null, review);
    }

    private boolean reconcileUsdt(Order order, PaymentChannel channel, PaymentAttempt attempt) {
        var config = payments.buildBepusdtConfig(channel);
        requireBinding(attempt, channel, config.apiUrl(), null);
        if (order.getUsdtTradeId() == null || order.getUsdtCryptoAmount() == null
                || order.getUsdtWalletAddress() == null) throw unavailable();
        if (attempt != null && attempt.getPendingTxId() != null) {
            confirmUsdt(order, attempt.getPendingTxId());
            return true;
        }
        var query = bepusdt.queryOrder(config, order.getUsdtTradeId());
        if (query == null || !order.getUsdtTradeId().equals(query.tradeId())
                || !order.getId().toString().equals(query.orderId())) throw unavailable();
        if (List.of("1", "3", "5").contains(query.status())) return false;
        if (!"2".equals(query.status())) throw unavailable();
        String network = order.getUsdtChain();
        String txid = normalizeUsdtHash(network, query.txid());
        String fiat = attempt != null ? attempt.getCurrency()
                : Objects.requireNonNullElse(order.getPaymentCurrency(), config.fiat());
        BigDecimal money = attempt != null ? attempt.getAmount()
                : Objects.requireNonNullElse(order.getPaymentAmount(), order.getActualAmount());
        boolean addressMatches = "usdt_bep20".equals(network)
                ? order.getUsdtWalletAddress().equalsIgnoreCase(query.walletAddress())
                : order.getUsdtWalletAddress().equals(query.walletAddress());
        if (txid == null || !config.tradeType().equals(query.tradeType())
                || !addressMatches || !fiat.equalsIgnoreCase(query.fiat()) || !sameAmount(money, query.money())
                || !sameAmount(new BigDecimal(order.getUsdtCryptoAmount()), query.cryptoAmount())) throw new PaidUnverified();
        confirmUsdt(order, txid);
        return true;
    }

    private void confirmUsdt(Order order, String txid) {
        String network = order.getUsdtChain();
        var result = chain.verifyForWebhook(network, txid, order.getUsdtWalletAddress(),
                order.getUsdtCryptoAmount(), order.getCreatedAt());
        if (result == null || !result.verified() || orders.existsByUsdtTxIdIgnoreCaseAndIdNot(txid, order.getId())) {
            throw new PaidUnverified();
        }
        acceptUsdt(inbox, order, network, txid, result);
    }

    static void acceptUsdt(PaymentInboxService inbox, Order order, String network, String txid,
                           TxidVerifyService.ChainVerifyResult result) {
        String key = "usdt_tx_" + network + "_" + txid;
        if (result.occurredAt() != null && result.occurredAt().isAfter(order.getExpiresAt())) {
            inbox.accept(key, "usdt", order.getId(), txid, "链上付款发生在订单截止后，需人工核对");
        } else {
            inbox.accept(key, "usdt", order.getId(), txid);
        }
    }

    static String normalizeUsdtHash(String network, String txid) {
        if (txid == null) return null;
        String pattern = switch (Objects.toString(network, "")) {
            case "usdt_trc20" -> "[a-fA-F0-9]{64}";
            case "usdt_bep20" -> "0[xX][a-fA-F0-9]{64}";
            default -> null;
        };
        return pattern == null || !txid.matches(pattern) ? null : txid.toLowerCase(Locale.ROOT);
    }

    private void importLegacy(Order order, PaymentChannel channel) {
        if ("epay".equals(channel.getProviderType()) && order.getEpayTradeNo() != null) {
            var config = payments.buildChannelConfig(channel);
            attemptService.rememberExisting(order.getId(), channel, config.apiUrl(), config.pid(), order.getActualAmount(), "CNY");
        } else if ("usdt".equals(channel.getProviderType()) && order.getUsdtTradeId() != null) {
            var config = payments.buildBepusdtConfig(channel);
            attemptService.rememberExisting(order.getId(), channel, config.apiUrl(), null,
                    Objects.requireNonNullElse(order.getPaymentAmount(), order.getActualAmount()),
                    Objects.requireNonNullElse(order.getPaymentCurrency(), config.fiat()));
        }
    }

    private PaymentChannel channel(Order order) {
        return channels.findByChannelCodeAndIsDeleted(order.getPaymentMethod(), 0).orElseThrow(this::unavailable);
    }

    private void requireBinding(PaymentAttempt attempt, PaymentChannel channel, String url, String merchant) {
        if (attempt != null && !PaymentAttemptService.matches(attempt, channel, url, merchant)) throw unavailable();
    }

    private static boolean sameAmount(BigDecimal amount, String observed) {
        try {
            return amount != null && observed != null && amount.compareTo(new BigDecimal(observed)) == 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE,
                "支付平台暂时无法核实这笔订单，请稍后查询，勿重复付款");
    }

    private static final class PaidUnverified extends BusinessException {
        private PaidUnverified() {
            super(ErrorCode.WEBHOOK_VERIFY_FAIL, "支付平台报已付款，商城仍需核实，请勿重复付款");
        }
    }
}
