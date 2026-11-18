package com.simplecard;

import com.simplecard.config.EmailVerificationProperties;
import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.OrderStatus;
import com.simplecard.constant.OrderType;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.Order;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.OrderRepository;
import com.simplecard.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客订单找回的端到端边界。
 *
 * <p>测试把 SMTP 伪造成「已配置」（host=localhost + 非占位符口令），
 * 并用 spy 截获明文验证码，从而在不真发信的前提下跑完整流程。
 *
 * <p>这里每一条断言都对应一个「不能被顺带放开」的边界，改动
 * {@link OrderAccessGuard} 或 {@link DeliverService} 前请先跑这个类。
 */
@SpringBootTest(properties = {
        "spring.mail.host=localhost",
        "spring.mail.password=test-only-password",
        "mail.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:guest_lookup;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class GuestOrderLookupIntegrationTest {

    @Autowired OrderLookupService lookup;
    @Autowired DeliverService deliver;
    @Autowired OrderAccessGuard guard;
    @Autowired OrderRepository orders;
    @Autowired EmailVerificationProperties props;
    @Autowired MockMvc mvc;
    /**
     * 用完全 mock 而不是 spy：{@code EmailServiceImpl} 的方法带 {@code @Async}，
     * 经 AOP 代理后注入点拿到的是原始 bean，spy 记录不到调用。
     * 这里本来也不需要真实发信，mock 掉既能截获明文验证码，又不会真的连 SMTP。
     */
    @MockitoBean EmailService emailService;

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void verifiedEmailUnlocksGuestOrdersOnly() {
        String email = randomEmail();
        String ip = randomIp();
        Order guest = guestOrder(email, "session-1");
        Order registered = registeredOrder(email, UUID.randomUUID());

        String code = sendAndCaptureCode(email, ip);
        OrderLookupService.VerifyResult verified = lookup.verifyCode(email, code, ip);
        assertThat(verified.guestOrders()).isEqualTo(1);
        assertThat(verified.registeredOrders()).isEqualTo(1);

        // 邮箱查单：只返回访客订单，注册订单不出现在结果里
        List<?> found = deliver.queryOrders(Map.of("emails", List.of(email)), null, verified.token());
        assertThat(found).hasSize(1);
        assertThat(found.toString())
                .contains(guest.getId().toString())
                .doesNotContain(registered.getId().toString());

        // 领取卡密：访客订单放行
        assertThat(deliver.deliverOrders(Map.of("order_ids", List.of(guest.getId().toString())),
                null, verified.token())).hasSize(1);

        // 注册订单：即使邮箱已验证，也必须登录本人账号
        assertThatThrownBy(() -> deliver.deliverOrders(
                Map.of("order_ids", List.of(registered.getId().toString())), null, verified.token()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> deliver.exportCardKeys(registered.getId(), null, verified.token()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void requestEmailCannotBeSwappedForAnotherOne() {
        String verifiedEmail = randomEmail();
        String ip = randomIp();
        guestOrder(verifiedEmail, "session-2");

        String code = sendAndCaptureCode(verifiedEmail, ip);
        String token = lookup.verifyCode(verifiedEmail, code, ip).token();

        // 换掉请求邮箱 → 403，而不是返回别人的订单
        assertThatThrownBy(() -> deliver.queryOrders(
                Map.of("emails", List.of(randomEmail())), null, token))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        // 大小写不同视为同一邮箱（与后端规范化一致）
        assertThat(deliver.queryOrders(
                Map.of("emails", List.of(verifiedEmail.toUpperCase())), null, token)).hasSize(1);
    }

    @Test
    void grantFromOneEmailCannotOpenAnotherEmailsOrder() {
        String emailA = randomEmail();
        String emailB = randomEmail();
        String ip = randomIp();
        guestOrder(emailA, "session-a");
        Order orderB = guestOrder(emailB, "session-b");

        String code = sendAndCaptureCode(emailA, ip);
        String token = lookup.verifyCode(emailA, code, ip).token();

        assertThatThrownBy(() -> deliver.deliverOrders(
                Map.of("order_ids", List.of(orderB.getId().toString())), null, token))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void sensitiveActionsIgnoreTheLookupGrant() throws Exception {
        String email = randomEmail();
        String ip = randomIp();
        Order guest = guestOrder(email, "original-session");

        String code = sendAndCaptureCode(email, ip);
        String token = lookup.verifyCode(email, code, ip).token();

        // 有凭证但没有原会话：提交 TXID 与重新支付都必须拒绝
        assertThatThrownBy(() -> guard.assertSensitiveAccess(guest, null)).isInstanceOf(BusinessException.class);

        mvc.perform(post("/api/orders/{id}/repay", guest.getId())
                        .contextPath("/api")
                        .header("X-Order-Lookup-Token", token)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/orders/{id}/txid-verify", guest.getId())
                        .contextPath("/api")
                        .header("X-Order-Lookup-Token", token)
                        .contentType("application/json").content("{\"txid\":\"abc\"}"))
                .andExpect(status().isForbidden());

        // 原会话仍然有效，说明拒绝的原因不是「谁都不许」而是「凭证不算数」
        assertThatCode(() -> guard.assertSensitiveAccess(guest, "original-session"))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidCodesShareOneIdenticalRejection() {
        String ip = randomIp();
        BusinessException missing = catchThrowableOfType(
                () -> lookup.verifyCode(randomEmail(), "123456", ip), BusinessException.class);

        String email = randomEmail();
        guestOrder(email, "session-3");
        sendAndCaptureCode(email, ip);
        BusinessException wrong = catchThrowableOfType(
                () -> lookup.verifyCode(email, "999999", ip), BusinessException.class);

        assertThat(missing.getCode()).isEqualTo(ErrorCode.EMAIL_CODE_INVALID);
        assertThat(wrong.getCode()).isEqualTo(ErrorCode.EMAIL_CODE_INVALID);
        // 文案必须一致，否则「码不存在」与「码不对」会被区分出来
        assertThat(missing.getMessage()).isEqualTo(wrong.getMessage());
    }

    @Test
    void entryIsClosedWhenVerificationIsSwitchedOff() {
        props.setEnabled(false);
        try {
            assertThatThrownBy(() -> lookup.sendCode(randomEmail(), randomIp()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_UNAVAILABLE));
            assertThatThrownBy(() -> lookup.verifyCode(randomEmail(), "123456", randomIp()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_UNAVAILABLE));
        } finally {
            props.setEnabled(true);
        }
    }

    @Test
    void emailWithoutGuestOrdersReceivesNoCodeButGetsTheSameAnswer() {
        String email = randomEmail();
        String ip = randomIp();
        // 只有注册订单，没有访客订单
        registeredOrder(email, UUID.randomUUID());

        long before = props.getResendIntervalSeconds();
        OrderLookupService.SendResult result = lookup.sendCode(email, ip);

        assertThat(result.retryAfterSeconds()).isEqualTo(before);
        // 没有订单就不发信，但响应与有订单时完全一样（含配额消耗）
        verify(emailService, org.mockito.Mockito.never()).sendVerificationCode(eq(email), anyString(), anyInt());
    }

    // ── helpers ──

    private String sendAndCaptureCode(String email, String ip) {
        lookup.sendCode(email, ip);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationCode(eq(email), code.capture(), anyInt());
        return code.getValue();
    }

    private Order guestOrder(String email, String sessionToken) {
        Order order = new Order();
        order.setOrderType(OrderType.DIRECT);
        order.setStatus(OrderStatus.DELIVERED);
        order.setActualAmount(BigDecimal.TEN);
        order.setTotalAmount(BigDecimal.TEN);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        order.setEmail(email);
        order.setSessionToken(sessionToken);
        return orders.saveAndFlush(order);
    }

    private Order registeredOrder(String email, UUID userId) {
        Order order = guestOrder(email, null);
        order.setUserId(userId);
        return orders.saveAndFlush(order);
    }

    private static String randomEmail() {
        return UUID.randomUUID().toString().substring(0, 8) + "@test.invalid";
    }

    private static String randomIp() {
        return "198.51.100." + (1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(250));
    }
}
