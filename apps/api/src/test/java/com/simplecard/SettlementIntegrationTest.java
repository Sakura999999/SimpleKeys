package com.simplecard;

import com.simplecard.constant.CardKeyStatus;
import com.simplecard.controller.AdminCurrencyController;
import com.simplecard.entity.*;
import com.simplecard.entity.Currency;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import com.simplecard.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.mail.host=localhost") @Transactional
class SettlementIntegrationTest {
    @Autowired SettlementService settlement;
    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired CurrencyRepository currencies;
    @Autowired ProductRepository products;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItems;
    @Autowired CartItemRepository carts;
    @Autowired PaymentChannelRepository channels;
    @Autowired AdminCurrencyController adminRates;
    @MockBean CardKeyRepository cards;
    @org.springframework.boot.test.mock.mockito.SpyBean InventoryLifecycleService inventory;
    @MockBean EpayService epay;
    @MockBean BepusdtService bepusdt;
    private Currency usd;

    @BeforeEach void setup() {
        doNothing().when(inventory).reserve(any());
        usd = rate("USD","7.2");
        var alipay = new PaymentChannel(); alipay.setChannelCode("alipay"); alipay.setChannelName("Alipay");
        alipay.setConfigData("{\"pid\":\"test\",\"key\":\"test\",\"api_url\":\"https://example.invalid\",\"notify_url\":\"https://example.invalid/notify\",\"return_url\":\"https://example.invalid/return\"}");
        channels.save(alipay);
        var crypto = new PaymentChannel(); crypto.setChannelCode("usdt_trc20"); crypto.setChannelName("USDT"); crypto.setProviderType("usdt");
        crypto.setConfigData("{\"fiat\":\"USD\",\"api_url\":\"https://example.invalid\",\"api_token\":\"test\",\"notify_url\":\"https://example.invalid/notify\"}"); channels.save(crypto);
        when(cards.countByProductIdAndSpecIdIsNullAndStatus(any(),eq(CardKeyStatus.AVAILABLE))).thenReturn(1000L);
        when(epay.createPayment(any(),any(),any(),any(),any(),any(),any())).thenReturn(new EpayService.EpayResult(1,"ok","trade","https://example.invalid/pay",null));
        when(bepusdt.createPayment(any(),any(),any(),any())).thenReturn(new BepusdtService.BepusdtPaymentResult("trade","wallet","10.001","https://example.invalid/crypto",900));
    }
    private Currency rate(String code,String value) {
        Currency currency = new Currency(); currency.setCode(code); currency.setName(code); currency.setSymbol(code);
        currency.setRateToCny(value == null ? null : new BigDecimal(value)); return currencies.save(currency);
    }
    private Product product(String code,String price) {
        Product product = new Product(); product.setTitle("Test " + code); product.setCurrency(code); product.setBasePrice(new BigDecimal(price)); product.setCategoryId(UUID.randomUUID()); return products.save(product);
    }
    private Map<String,Object> request(Product p,String method) {
        return new HashMap<>(Map.of("product_id",p.getId().toString(),"quantity",1,"payment_method",method));
    }
    private Map<String,Object> confirmed(Map<String,Object> request) {
        request.put("quote_token",orders.quote(request,null,"test-session").quoteToken()); return request;
    }
    @Test void usdOrderUsesCnyForEpayAndPersistsSnapshot() {
        Product p = product("USD","10");
        var created = orders.createDirectOrder(confirmed(request(p,"alipay")),null,null,"test-session");
        var stored = orderRepository.findById(created.orderId()).orElseThrow();
        assertThat(stored.getActualAmount()).isEqualByComparingTo("72.00");
        assertThat(stored.getPaymentCurrency()).isEqualTo("CNY");
        var item = orderItems.findByOrderId(stored.getId()).getFirst();
        assertThat(item.getSourceCurrency()).isEqualTo("USD");
        assertThat(item.getSourceUnitPrice()).isEqualByComparingTo("10");
        assertThat(item.getExchangeRate()).isEqualByComparingTo("7.2");
        usd.setRateToCny(new BigDecimal("8")); currencies.save(usd);
        payments.createPayment(stored.getId(),"alipay",new BigDecimal("0.01"));
        verify(epay).createPayment(any(),eq(stored.getId().toString()),eq("alipay"),any(),eq(new BigDecimal("72.00")),any(),any());
    }
    @Test void mixedCurrencyCartConvertsEachLineBeforeSumming() {
        UUID user = UUID.randomUUID(); Product dollar = product("USD","10"); Product yuan = product("CNY","50");
        for (Product p : List.of(dollar,yuan)) { var item = new CartItem(); item.setUserId(user); item.setProductId(p.getId()); item.setQuantity(p == dollar ? 2 : 1); carts.save(item); }
        Map<String,Object> request = new HashMap<>(Map.of("from_cart",true,"payment_method","alipay"));
        var quote = orders.quote(request,user,null); assertThat(quote.totalAmount()).isEqualByComparingTo("194.00");
        request.put("quote_token",quote.quoteToken());
        var created = orders.createCartOrder(request,user,null,null);
        assertThat(created.actualAmount()).isEqualByComparingTo("194.00");
        assertThat(orderItems.findByOrderId(created.orderId())).hasSize(2);
        assertThat(carts.findByUserId(user)).isEmpty();
    }
    @Test void staleOrMissingConfirmationDoesNotCreateAnOrder() {
        var request = confirmed(request(product("USD","10"),"alipay"));
        usd.setRateToCny(new BigDecimal("7.3")); currencies.save(usd);
        assertThatThrownBy(() -> orders.createDirectOrder(request,null,null,"test-session")).isInstanceOf(BusinessException.class).hasMessageContaining("已变化");
        request.remove("quote_token");
        assertThatThrownBy(() -> orders.createDirectOrder(request,null,null,"test-session")).isInstanceOf(BusinessException.class);
        assertThat(orderRepository.count()).isZero();
    }
    @Test void missingRatesFailClosedAndNeverCallGateways() {
        for (String code : List.of("EUR","GBP","USDT")) {
            rate(code,null);
            assertThatThrownBy(() -> orders.quote(request(product(code,"10"),"alipay"),null,null)).isInstanceOf(BusinessException.class).hasMessageContaining("汇率");
        }
        verifyNoInteractions(epay,bepusdt);
    }
    @Test void usdtGatewayGetsSnapshottedUsdAmountInsteadOfCnyNumber() {
        var created = orders.createDirectOrder(confirmed(request(product("USD","10"),"usdt_trc20")),null,null,"test-session");
        var order = orderRepository.findById(created.orderId()).orElseThrow();
        assertThat(order.getActualAmount()).isEqualByComparingTo("72.00");
        assertThat(order.getPaymentAmount()).isEqualByComparingTo("10.00");
        usd.setRateToCny(new BigDecimal("8")); currencies.save(usd);
        payments.createPayment(created.orderId(),"usdt_trc20",new BigDecimal("999"));
        verify(bepusdt).createPayment(argThat(config -> config.fiat().equals("USD")),eq(created.orderId().toString()),eq(new BigDecimal("10.00")),any());
        assertThat(order.getUsdtCryptoAmount()).isEqualTo("10.001");
    }
    @Test void supportsAllConfiguredCurrenciesAndRoundsPerUnit() {
        rate("EUR","8"); rate("GBP","9"); rate("USDT","7.1");
        for (var pair : Map.of("CNY","1.00","USD","7.20","EUR","8.00","GBP","9.00","USDT","7.10").entrySet()) {
            assertThat(orders.quote(request(product(pair.getKey(),"1"),"alipay"),null,null).totalAmount()).isEqualByComparingTo(pair.getValue());
        }
        usd.setRateToCny(new BigDecimal("7.12345678")); currencies.save(usd);
        var request = request(product("USD","1.01"),"alipay"); request.put("quantity",3);
        assertThat(orders.quote(request,null,null).totalAmount()).isEqualByComparingTo("21.57");
    }
    @Test void invalidAdminRatesAreRejectedAndAllChangesRollback() {
        assertThatThrownBy(() -> adminRates.save(Map.of("USD","0"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminRates.save(Map.of("USD","-1"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminRates.save(Map.of("USD","NaN"))).isInstanceOf(BusinessException.class);
        assertThat(usd.getRateToCny()).isEqualByComparingTo("7.2");
    }
    @Test void explicitGatewayRejectionClosesOrderAlsoOnRetryPath() {
        var created = orders.createDirectOrder(confirmed(request(product("USD","10"),"alipay")),null,null,"test-session");
        when(epay.createPayment(any(),any(),any(),any(),any(),any(),any())).thenThrow(new com.simplecard.exception.PaymentRejectedException(400,"rejected"));
        assertThatThrownBy(() -> payments.createPayment(created.orderId(),"alipay",created.actualAmount())).isInstanceOf(com.simplecard.exception.PaymentRejectedException.class);
        assertThat(orderRepository.findById(created.orderId()).orElseThrow().getStatus()).isEqualTo(com.simplecard.constant.OrderStatus.EXPIRED);
    }
    @Test void ambiguousGatewayFailureKeepsReservationLifetime() {
        var created = orders.createDirectOrder(confirmed(request(product("USD","10"),"alipay")),null,null,"test-session");
        when(epay.createPayment(any(),any(),any(),any(),any(),any(),any())).thenThrow(new BusinessException(400,"network timeout"));
        assertThatThrownBy(() -> payments.createPayment(created.orderId(),"alipay",created.actualAmount())).isInstanceOf(BusinessException.class);
        assertThat(orderRepository.findById(created.orderId()).orElseThrow().getStatus()).isEqualTo(com.simplecard.constant.OrderStatus.PENDING);
    }
}
