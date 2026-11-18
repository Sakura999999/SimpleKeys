package com.simplecard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.config.*;
import com.simplecard.constant.*;
import com.simplecard.context.RequestContext;
import com.simplecard.controller.OrderController;
import com.simplecard.entity.*;
import com.simplecard.entity.Order;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import com.simplecard.service.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TrafficSafetyTest {
    @AfterEach void clear() { RequestContext.clear(); }
    @Test void txidMustVerifyOwnerBeforeQuotasDatabaseReviewOrExternalCalls() {
        OrderRepository orders=mock(OrderRepository.class);var unmatched=mock(UnmatchedTransactionRepository.class);
        var verifier=mock(TxidVerifyService.class);var limiter=mock(ApiTrafficLimiter.class);
        OrderController controller=controller(orders,unmatched,verifier,limiter);
        Order order=pending();order.setUserId(UUID.randomUUID());when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        RequestContext.set(new RequestContext.UserInfo(UUID.randomUUID(),"other","USER"));
        assertThatThrownBy(()->controller.submitTxidForVerification(order.getId(),Map.of("txid","a".repeat(64)),null))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(limiter,verifier,unmatched);
    }
    @Test void guestTxidRequiresOriginalSessionAnd429NeverCreatesReview() {
        OrderRepository orders=mock(OrderRepository.class);var unmatched=mock(UnmatchedTransactionRepository.class);
        var verifier=mock(TxidVerifyService.class);var limiter=mock(ApiTrafficLimiter.class);
        OrderController controller=controller(orders,unmatched,verifier,limiter);
        Order order=pending();order.setSessionToken("original-secret");when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        assertThatThrownBy(()->controller.submitTxidForVerification(order.getId(),Map.of("txid","a".repeat(64)),"different"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(403));
        verifyNoInteractions(limiter,verifier,unmatched);
        doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"limited",HttpStatus.TOO_MANY_REQUESTS))
                .when(limiter).external(eq("txid-upstream"),eq("txid-order"),eq(order.getId()),eq(3));
        assertThatThrownBy(()->controller.submitTxidForVerification(order.getId(),Map.of("txid","a".repeat(64)),"original-secret"))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(429));
        verifyNoInteractions(verifier);verify(unmatched,never()).save(any());
    }
    @Test void sensitiveGuestAccessDoesNotSilentlyAcceptLegacyMissingSessions() {
        Order order=pending();OrderAccessGuard guard=new OrderAccessGuard(new com.simplecard.service.OrderLookupGrantStore());
        guard.assertAccess(order,null); // read compatibility: guest UUID remains a capability
        assertThatThrownBy(()->guard.assertSensitiveAccess(order,null)).isInstanceOf(BusinessException.class);
        order.setSessionToken("original");guard.assertSensitiveAccess(order,"original");
    }
    private Order pending() {
        Order o=new Order();o.setId(UUID.randomUUID());o.setStatus(OrderStatus.PENDING);o.setPaymentMethod("usdt_trc20");o.setUsdtChain("usdt_trc20");
        o.setUsdtWalletAddress("test-wallet");o.setUsdtCryptoAmount("1");return o;
    }
    private OrderController controller(OrderRepository orders,UnmatchedTransactionRepository unmatched,TxidVerifyService verify,ApiTrafficLimiter limiter) {
        SiteConfigRepository configs=mock(SiteConfigRepository.class);when(configs.findByConfigKey(anyString())).thenReturn(Optional.empty());
        return new OrderController(mock(OrderService.class),mock(InventoryLifecycleService.class),mock(DeliverService.class),orders,
                unmatched,verify,mock(PaymentService.class),mock(ClientIpResolver.class),new OrderAccessGuard(new com.simplecard.service.OrderLookupGrantStore()),limiter,configs);
    }
    @Test void onlyConfiguredProxyCanSupplyClientIdentityAndHostnamesAreNeverResolved() {
        ClientIpResolver resolver=new ClientIpResolver();ReflectionTestUtils.setField(resolver,"trustedProxiesConfig","127.0.0.1,172.20.0.1,::1");
        MockHttpServletRequest r=new MockHttpServletRequest();r.setRemoteAddr("203.0.113.10");r.addHeader("X-Real-IP","8.8.8.8");
        assertThat(resolver.resolve(r)).isEqualTo("203.0.113.10");r.setRemoteAddr("172.20.0.1");
        assertThat(resolver.resolve(r)).isEqualTo("8.8.8.8");r.removeHeader("X-Real-IP");
        r.addHeader("X-Forwarded-For","9.9.9.9, 198.51.100.4,172.20.0.1");assertThat(resolver.resolve(r)).isEqualTo("198.51.100.4");
        r.removeHeader("X-Forwarded-For");r.addHeader("X-Real-IP","attacker.example");assertThat(resolver.resolve(r)).isEqualTo("172.20.0.1");
    }
    @Test void queryAndPaginationBoundsAreCheckedBeforeAnyDatabaseWork() {
        PublicRequestBounds.pagination(1,100,"x".repeat(64));
        for (Runnable invalid:List.<Runnable>of(()->PublicRequestBounds.pagination(0,10,null),()->PublicRequestBounds.pagination(1,101,null),
                ()->PublicRequestBounds.pagination(1,10,"x".repeat(65)),()->PublicRequestBounds.strings(Map.of("order_ids",Collections.nCopies(51,"x")),"order_ids",50,36),
                ()->PublicRequestBounds.strings(Map.of("order_ids",List.of(1)),"order_ids",50,36)))
            assertThatThrownBy(invalid::run).isInstanceOf(BusinessException.class);
    }
    @Test void callbacksBypassConsumerBucketAndTheirOwnSaturationReturnsRetryNot429() throws Exception {
        var configs=mock(SiteConfigRepository.class);
        RateLimitFilter ordinary=new RateLimitFilter(configs,new ObjectMapper(),new ClientIpResolver());
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/payments/webhook/epay");request.setContextPath("/api");
        MockFilterChain passed=new MockFilterChain();ordinary.doFilter(request,new MockHttpServletResponse(),passed);
        assertThat(passed.getRequest()).isSameAs(request);verifyNoInteractions(configs);
        WebhookTrafficFilter callback=new WebhookTrafficFilter(1,1);callback.doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        MockHttpServletResponse saturated=new MockHttpServletResponse();MockFilterChain rejected=new MockFilterChain();
        callback.doFilter(newRequest(),saturated,rejected);
        assertThat(saturated.getStatus()).isEqualTo(503);assertThat(saturated.getContentAsString()).isEqualTo("retry");assertThat(rejected.getRequest()).isNull();
    }
    private MockHttpServletRequest newRequest() { var r=new MockHttpServletRequest("GET","/api/payments/webhook/epay");r.setContextPath("/api");return r; }
    @Test void enabledRegistrationTurnstileAcceptsSuccessAndFailsClosedOnProviderErrors() throws Exception {
        for(boolean success:new boolean[]{true,false}) {
            var configs=mock(SiteConfigRepository.class);SiteConfig setting=new SiteConfig();setting.setConfigValue("true");
            when(configs.findByConfigKey("turnstile_enabled")).thenReturn(Optional.of(setting));
            TurnstileFilter filter=new TurnstileFilter(configs,new ObjectMapper(),new ClientIpResolver());ReflectionTestUtils.setField(filter,"secretKey","test-secret");
            RestTemplate http=(RestTemplate)ReflectionTestUtils.getField(filter,"turnstileRestTemplate");
            MockRestServiceServer server=MockRestServiceServer.bindTo(http).build();
            var expectation=server.expect(requestTo("https://challenges.cloudflare.com/turnstile/v0/siteverify"));
            if(success)expectation.andRespond(withSuccess("{\"success\":true}",MediaType.APPLICATION_JSON));else expectation.andRespond(withServerError());
            var r=new MockHttpServletRequest("POST","/api/auth/register");r.setContextPath("/api");r.addHeader("X-Turnstile-Token","test-token");
            MockFilterChain chain=new MockFilterChain();MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(r,response,chain);
            if(success) assertThat(chain.getRequest()).isNotNull();else {assertThat(chain.getRequest()).isNull();assertThat(response.getStatus()).isEqualTo(400);}
            server.verify();
        }
    }
    @Test void localFallbackAndBucketRegistrationHaveHardBounds() {
        LocalRequestWindows local=new LocalRequestWindows(4);
        assertThat(local.admit("scope","a",1000,10,2)).isTrue();assertThat(local.admit("scope","b",1000,10,2)).isTrue();
        assertThat(local.admit("scope","c",1000,10,2)).isTrue();assertThat(local.admit("scope","d",1000,10,2)).isFalse();
        assertThat(local.size()).isEqualTo(4);
    }
    @Test void provider429AndTimeoutNeverBecomeManualReviewRecords() {
        for(RuntimeException failure:List.of(org.springframework.web.client.HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,"busy",HttpHeaders.EMPTY,new byte[0],null),
                new org.springframework.web.client.ResourceAccessException("timeout"))) {
            RestTemplate http=mock(RestTemplate.class);when(http.postForObject(anyString(),anyMap(),eq(String.class))).thenThrow(failure);
            var unmatched=mock(UnmatchedTransactionRepository.class);
            var service=new com.simplecard.service.impl.TxidVerifyServiceImpl(unmatched,http,new ObjectMapper());
            assertThatThrownBy(()->service.verifyAndProcess(pending(),"a".repeat(64)))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isIn(429,503));
            verifyNoInteractions(unmatched);
        }
    }
    @Test void authenticatedFilterCanLimitUserButDoesNotTouchProductDetailsOrCallbacks() throws Exception {
        var limiter=mock(ApiTrafficLimiter.class);ClientIpResolver ip=mock(ClientIpResolver.class);when(ip.resolve(any())).thenReturn("203.0.113.1");
        ApiTrafficFilter filter=new ApiTrafficFilter(limiter,ip,new ObjectMapper());
        UUID user=UUID.randomUUID();RequestContext.set(new RequestContext.UserInfo(user,"user","USER"));
        var request=new MockHttpServletRequest("POST","/api/orders");request.setContextPath("/api");
        filter.doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        verify(limiter).check("create-order","203.0.113.1",false);verify(limiter).check("create-user",user.toString(),false);clearInvocations(limiter);
        for(String path:List.of("/api/products/"+UUID.randomUUID(),"/api/payments/webhook/epay")) {
            var r=new MockHttpServletRequest("GET",path);r.setContextPath("/api");filter.doFilter(r,new MockHttpServletResponse(),new MockFilterChain());
        }
        verifyNoInteractions(limiter);
    }
    @Test void sharedRedisAddressIsRejectedBeforeConnecting() {
        ApiRateLimitProperties limits=new ApiRateLimitProperties();limits.setAddress("redis://127.0.0.1:6379");
        assertThatThrownBy(()->new ApiLimitRedisConfig().apiLimiterRedis(limits,new ProductGuardProperties(),"redis://127.0.0.1:6379"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void encodedRoutesAndCustomContextUseTheSamePolicy() throws Exception {
        var limiter=mock(ApiTrafficLimiter.class);var ip=mock(ClientIpResolver.class);when(ip.resolve(any())).thenReturn("203.0.113.2");
        var request=new MockHttpServletRequest("POST","/custom/%6frders");request.setContextPath("/custom");
        new ApiTrafficFilter(limiter,ip,new ObjectMapper()).doFilter(request,new MockHttpServletResponse(),new MockFilterChain());
        verify(limiter).check("create-order","203.0.113.2",false);
        assertThat(RequestPaths.application(request)).isEqualTo("/orders");
    }
}
