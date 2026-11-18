package com.simplecard.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.entity.Order;
import com.simplecard.entity.PaymentChannel;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.PaymentAttemptRepository;
import com.simplecard.repository.PaymentChannelRepository;
import com.simplecard.repository.VerifiedPaymentEventRepository;
import com.simplecard.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BepusdtReconciliationTest {
    @Test
    void recoversMissedNotificationUsingCheckoutInfoAndIndependentChainVerification() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setPaymentMethod("usdt_trc20");
        order.setUsdtChain("usdt_trc20");
        order.setUsdtTradeId("gateway-trade");
        order.setUsdtWalletAddress("test-wallet");
        order.setUsdtCryptoAmount("2");
        order.setActualAmount(new BigDecimal("15.00"));
        order.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        order.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        String hash = "a".repeat(64);
        RestTemplate http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://example.invalid/api/v1/pay/info")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"trade_id\":\"gateway-trade\"}"))
                .andRespond(withSuccess("""
                        {"status_code":200,"data":{"status":2,"order_id":"%s","trade_id":"gateway-trade",
                        "trade_type":"usdt.trc20","token":"test-wallet","actual_amount":"2.0","money":"15",
                        "fiat":"CNY","trade_url":"https://tronscan.org/#/transaction/%s"}}
                        """.formatted(order.getId(), hash), MediaType.APPLICATION_JSON));
        PaymentChannel channel = new PaymentChannel();
        channel.setProviderType("usdt");
        var channels = mock(PaymentChannelRepository.class);
        when(channels.findByChannelCodeAndIsDeleted("usdt_trc20", 0)).thenReturn(Optional.of(channel));
        var payment = mock(PaymentServiceImpl.class);
        when(payment.buildBepusdtConfig(channel)).thenReturn(new BepusdtService.BepusdtConfig(
                "https://example.invalid", "test-token", "notify", "", "usdt.trc20", "CNY", 900, ""));
        var wallet = mock(TxidVerifyService.class);
        when(wallet.verifyForWebhook("usdt_trc20", hash, "test-wallet", "2", order.getCreatedAt()))
                .thenReturn(new TxidVerifyService.ChainVerifyResult(true, "verified", order.getCreatedAt().plusSeconds(10)));
        var inbox = mock(PaymentInboxService.class);
        var service = new PaymentReconciliationService(mock(PaymentAttemptRepository.class),
                mock(PaymentAttemptService.class), mock(OrderRepository.class), channels,
                mock(VerifiedPaymentEventRepository.class), payment, mock(EpayService.class),
                new BepusdtServiceImpl(http, new ObjectMapper()), wallet, inbox);

        service.refresh(order);

        server.verify();
        verify(inbox).accept("usdt_tx_usdt_trc20_" + hash, "usdt", order.getId(), hash);
    }
}
