package com.simplecard;

import com.simplecard.constant.*;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.*;
import com.simplecard.entity.Order;
import com.simplecard.exception.BusinessException;
import com.simplecard.model.request.LoginRequest;
import com.simplecard.repository.*;
import com.simplecard.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.mail.host=localhost","spring.datasource.url=jdbc:h2:mem:api_safety;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class ApiSafetyIntegrationTest {
    @DynamicPropertySource static void pg(DynamicPropertyRegistry r) {
        if("true".equals(System.getenv("PRODUCT_GUARD_POSTGRES_TESTS"))) {
            r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:55440/product_guard_test");
            r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"guard_test");r.add("spring.datasource.password",()->"guard_test_only");
        }
    }
    @Autowired AuthService auth;
    @Autowired PasswordEncoder passwords;
    @Autowired UserRepository users;
    @Autowired DeliverService deliver;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository items;
    @Autowired CardKeyRepository cards;
    @Autowired OperationLogRepository audits;
    @Autowired SiteConfigRepository configs;
    @Autowired MockMvc mvc;
    @Autowired com.simplecard.utils.JwtUtils jwt;
    @MockitoSpyBean ApiTrafficLimiter traffic;
    @AfterEach void clear(){RequestContext.clear();}
    @Test void concurrentLoginFailuresAreNotLostAndTheExistingLockIsRetained() throws Exception {
        SiteConfig setting=new SiteConfig();setting.setConfigKey("login_attempt_limit");setting.setConfigValue("5");configs.saveAndFlush(setting);
        User user=new User();user.setUsername("parallel-"+UUID.randomUUID());user.setEmail(UUID.randomUUID()+"@test.invalid");
        user.setPasswordHash(passwords.encode("correct-password"));user.setRole(UserRole.USER);user=users.saveAndFlush(user);
        String account=user.getUsername();ExecutorService workers=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<?>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(workers.submit(()->{start.await();LoginRequest req=new LoginRequest();req.setAccount(account);req.setPassword("wrong-password");
                assertThatThrownBy(()->auth.login(req,null)).isInstanceOf(BusinessException.class);return null;}));
            start.countDown();for(var call:calls)call.get(10,TimeUnit.SECONDS);
            assertThat(users.findById(user.getId()).orElseThrow().getFailedLoginAttempts()).isEqualTo(4);
            LoginRequest req=new LoginRequest();req.setAccount(account);req.setPassword("wrong-password");
            assertThatThrownBy(()->auth.login(req,null)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
            assertThat(users.findById(user.getId()).orElseThrow().getLockUntil()).isAfter(LocalDateTime.now());
        } finally {workers.shutdownNow();}
    }
    @Test void guestExportIsAuditedWithoutPersistingCardOrSessionSecrets() {
        Order o=new Order();o.setOrderType(OrderType.DIRECT);o.setStatus(OrderStatus.DELIVERED);o.setActualAmount(BigDecimal.TEN);
        o.setTotalAmount(BigDecimal.TEN);o.setExpiresAt(LocalDateTime.now().plusMinutes(5));o.setSessionToken("session-not-for-logs");o=orders.saveAndFlush(o);
        OrderItem item=new OrderItem();item.setOrderId(o.getId());item.setProductId(UUID.randomUUID());item.setProductTitle("Test product");
        item.setQuantity(1);item.setUnitPrice(BigDecimal.TEN);item.setSubtotal(BigDecimal.TEN);item=items.saveAndFlush(item);
        CardKey key=new CardKey();key.setProductId(item.getProductId());key.setOrderId(o.getId());key.setOrderItemId(item.getId());
        key.setStatus(CardKeyStatus.SOLD);key.setContent("secret-card-never-log");cards.saveAndFlush(key);
        assertThat(deliver.exportCardKeys(o.getId(),"session-not-for-logs")).contains("secret-card-never-log");
        var log=audits.findAll().stream().filter(a->"CARD_KEYS_EXPORT_PREPARED".equals(a.getAction())).findFirst().orElseThrow();
        assertThat(log.getUserId()).isEqualTo(new UUID(0,0));assertThat(log.getDetail()).contains("PREPARED").doesNotContain("secret-card","session-not-for-logs");
    }
    @Test void oversizedBatchIsRejectedWithoutFetchingOrders() {
        assertThatThrownBy(()->deliver.queryOrders(Map.of("order_ids",Collections.nCopies(51,UUID.randomUUID().toString())),null))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }
    @Test void emailLookupIsScopedToCallerInSqlAndGuestNeedsSession() {
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();String email=UUID.randomUUID()+"@test.invalid";
        Order a=lookupOrder(email,owner,null),b=lookupOrder(email,other,null),guest=lookupOrder(email,null,"guest-proof");
        RequestContext.set(new RequestContext.UserInfo(owner,"owner","USER"));
        var result=deliver.queryOrders(Map.of("emails",List.of(email)),null);
        assertThat(result).hasSize(1);assertThat(result.toString()).contains(a.getId().toString()).doesNotContain(b.getId().toString(),guest.getId().toString());
        RequestContext.clear();result=deliver.queryOrders(Map.of("emails",List.of(email)),"guest-proof");
        assertThat(result).hasSize(1);assertThat(result.toString()).contains(guest.getId().toString()).doesNotContain(a.getId().toString());
        assertThatThrownBy(()->deliver.queryOrders(Map.of("emails",List.of(email)),null)).isInstanceOf(BusinessException.class);
    }
    private Order lookupOrder(String email,UUID owner,String token) {
        Order o=new Order();o.setOrderType(OrderType.DIRECT);o.setStatus(OrderStatus.DELIVERED);o.setActualAmount(BigDecimal.TEN);o.setTotalAmount(BigDecimal.TEN);
        o.setExpiresAt(LocalDateTime.now().plusMinutes(5));o.setEmail(email);o.setUserId(owner);o.setSessionToken(token);return orders.saveAndFlush(o);
    }
    @Test void realSecurityChainMakesAuthenticatedUserAvailableBeforeTheTrafficFilter() throws Exception {
        User user=new User();user.setUsername("http-"+UUID.randomUUID());user.setEmail(UUID.randomUUID()+"@test.invalid");
        user.setPasswordHash(passwords.encode("test-password"));user.setRole(UserRole.USER);user=users.saveAndFlush(user);
        String token=jwt.generateToken(user.getId(),user.getUsername(),UserRole.USER.name());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders").contextPath("/api")
                .header("Authorization","Bearer "+token).contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        org.mockito.Mockito.verify(traffic).check("create-user",user.getId().toString(),false);
        assertThat(RequestContext.getUserId()).isNull();
    }
}
