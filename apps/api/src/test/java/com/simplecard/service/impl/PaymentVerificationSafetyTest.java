package com.simplecard.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.entity.Order;
import com.simplecard.entity.PaymentChannel;
import com.simplecard.entity.UnmatchedTransaction;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.PaymentChannelRepository;
import com.simplecard.repository.UnmatchedTransactionRepository;
import com.simplecard.repository.WebhookEventRepository;
import com.simplecard.service.BepusdtService;
import com.simplecard.service.EpayService;
import com.simplecard.service.InventoryLifecycleService;
import com.simplecard.service.PaymentInboxService;
import com.simplecard.service.TxidVerifyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentVerificationSafetyTest {
    private static final String TXID = "a".repeat(64);
    private static final String WALLET = "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb";

    @Test
    void exactUserSubmittedTransferStillRequiresOrderOwnershipReview() {
        Order order = usdtOrder();
        RestTemplate http = tronNode(order, "SUCCESS", 1_000_000);
        var reviews = mock(UnmatchedTransactionRepository.class);
        var service = new TxidVerifyServiceImpl(reviews, http, new ObjectMapper());

        var result = service.verifyAndProcess(order, TXID);

        assertThat(result.result()).isEqualTo(TxidVerifyService.VerifyResult.PENDING_REVIEW);
        var review = ArgumentCaptor.forClass(UnmatchedTransaction.class);
        verify(reviews).save(review.capture());
        assertThat(review.getValue().getVerifyReason()).isEqualTo("ORDER_OWNERSHIP_REVIEW_REQUIRED");
    }

    @Test
    void webhookRequiresExplicitlySuccessfulFinalReceiptAndExactAmount() {
        Order order = usdtOrder();
        assertThat(verifyTron(order, "REVERT", 1_000_000)).isNull();
        assertThat(verifyTron(order, "SUCCESS", 999_999).verified()).isFalse();
        assertThat(verifyTron(order, "SUCCESS", 1_000_000).verified()).isTrue();
    }

    @Test
    void bscReceiptMustBeOnMainnetAndInTheFinalCanonicalChain() {
        assertThat(verifyBsc("0x61", "0x20", "0xblock")).isNull();
        assertThat(verifyBsc("0x38", "0xf", "0xblock")).isNull();
        assertThat(verifyBsc("0x38", "0x20", "0xother")).isNull();
        assertThat(verifyBsc("0x38", "0x20", "0xblock").verified()).isTrue();
    }

    @Test
    void epayRequiresMatchingMerchantAndSuccessfulIndependentQuery() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setPaymentMethod("alipay");
        order.setActualAmount(new BigDecimal("1.00"));
        order.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        order.setEpayTradeNo("gateway-order");
        PaymentChannel channel = new PaymentChannel();
        channel.setProviderType("epay");
        var channels = mock(PaymentChannelRepository.class);
        when(channels.findByChannelCodeAndIsDeleted("alipay", 0)).thenReturn(Optional.of(channel));
        var orders = mock(OrderRepository.class);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        var payment = mock(PaymentServiceImpl.class);
        var config = new EpayService.ChannelConfig("merchant", "test-key", "https://example.invalid", "notify", "return");
        when(payment.buildChannelConfig(channel)).thenReturn(config);
        when(payment.epayType("alipay")).thenReturn("alipay");
        var epay = mock(EpayService.class);
        when(epay.verifySign(eq("test-key"), anyMap(), eq("test-signature"))).thenReturn(true);
        var inbox = mock(PaymentInboxService.class);
        var webhook = new WebhookServiceImpl(mock(WebhookEventRepository.class), orders, inbox,
                mock(InventoryLifecycleService.class), channels, epay, mock(BepusdtService.class),
                new ObjectMapper(), payment, mock(TxidVerifyService.class),
                mock(com.simplecard.service.PaymentAttemptService.class));
        var params = new java.util.HashMap<>(Map.of(
                "pid", "wrong", "type", "alipay", "out_trade_no", order.getId().toString(),
                "trade_no", "gateway-order", "money", "1.00", "trade_status", "TRADE_SUCCESS",
                "sign", "test-signature", "sign_type", "MD5"));

        assertThat(webhook.processEpayCallback(params)).isEqualTo("FAIL");
        verifyNoInteractions(epay, inbox);
        params.put("pid", "merchant");
        assertThat(webhook.processEpayCallback(params)).isEqualTo("FAIL");
        verifyNoInteractions(inbox);

        when(epay.queryOrder(config, order.getId().toString()))
                .thenReturn(new EpayService.OrderQueryResult("1", "1.00", "gateway-order"));
        assertThat(webhook.processEpayCallback(params)).isEqualTo("SUCCESS");
        verify(inbox).accept("epay_gateway-order", "epay", order.getId(), null);
    }

    private TxidVerifyService.ChainVerifyResult verifyTron(Order order, String receiptResult, long atomicAmount) {
        var service = new TxidVerifyServiceImpl(mock(UnmatchedTransactionRepository.class),
                tronNode(order, receiptResult, atomicAmount), new ObjectMapper());
        return service.verifyForWebhook(order.getUsdtChain(), TXID, WALLET, "1", order.getCreatedAt());
    }

    private TxidVerifyService.ChainVerifyResult verifyBsc(String chainId, String finalizedHeight, String canonicalHash) {
        String recipient = "0x" + "2".repeat(40);
        String hash = "0x" + TXID;
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(anyString(), anyMap(), eq(String.class))).thenAnswer(request -> {
            Map<?, ?> rpc = request.getArgument(1);
            return switch (rpc.get("method").toString()) {
                case "eth_chainId" -> "{\"result\":\"" + chainId + "\"}";
                case "eth_getBlockByNumber" -> rpc.get("params").equals(java.util.List.of("finalized", false))
                        ? "{\"result\":{\"number\":\"" + finalizedHeight + "\"}}"
                        : "{\"result\":{\"hash\":\"" + canonicalHash + "\",\"timestamp\":\"0x3000\"}}";
                case "eth_getTransactionReceipt" -> """
                        {"result":{"status":"0x1","transactionHash":"%s","blockNumber":"0x10","blockHash":"0xblock",
                        "logs":[{"address":"0x55d398326f99059ff775485246999027b3197955",
                        "topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef","0x%064x","0x%s%s"],
                        "data":"0x%064x"}]}}
                        """.formatted(hash, 1, "0".repeat(24), recipient.substring(2), 1_000_000_000_000_000_000L);
                default -> throw new IllegalArgumentException("Unexpected RPC request");
            };
        });
        var service = new TxidVerifyServiceImpl(mock(UnmatchedTransactionRepository.class), http, new ObjectMapper());
        return service.verifyForWebhook("usdt_bep20", hash, recipient, "1",
                LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(0x2000), ZoneId.systemDefault()));
    }

    private RestTemplate tronNode(Order order, String receiptResult, long atomicAmount) {
        RestTemplate http = mock(RestTemplate.class);
        long blockTimestamp = order.getCreatedAt().plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        when(http.postForObject(eq("https://api.trongrid.io/walletsolidity/gettransactioninfobyid"),
                eq(Map.of("value", TXID)), eq(String.class)))
                .thenReturn("""
                        {"id":"%s","receipt":{"result":"%s"},"blockTimeStamp":%d,
                         "log":[{"address":"a614f803b6fd780986a42c78ec9c7f77e6ded13c","topics":[
                         "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
                         "%064x","%064x"],"data":"%064x"}]}
                        """.formatted(TXID, receiptResult, blockTimestamp, 1, 0, atomicAmount));
        return http;
    }

    private static Order usdtOrder() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        order.setUsdtChain("usdt_trc20");
        order.setUsdtWalletAddress(WALLET);
        order.setUsdtCryptoAmount("1.000000");
        return order;
    }
}
