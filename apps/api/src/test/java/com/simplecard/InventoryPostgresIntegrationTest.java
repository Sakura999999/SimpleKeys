package com.simplecard;

import com.simplecard.constant.*;
import com.simplecard.entity.*;
import com.simplecard.repository.*;
import com.simplecard.service.*;
import com.simplecard.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Uses ONLY the disposable inventory_test database documented in docs/inventory-reservation.md. */
@EnabledIfEnvironmentVariable(named="INVENTORY_PG_TESTS", matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/inventory_test",
    "spring.datasource.username=inventory_test", "spring.datasource.password=inventory_test",
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "spring.sql.init.mode=never", "spring.mail.host=localhost", "mail.enabled=false"})
class InventoryPostgresIntegrationTest {
    @Autowired InventoryLifecycleService inventory;
    @Autowired OrderService orderService;
    @Autowired DeliverService delivery;
    @Autowired AdminCardKeyService adminCards;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository items;
    @Autowired CardKeyRepository cards;
    @Autowired ProductRepository products;
    @Autowired PaymentChannelRepository channels;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired SiteConfigRepository configs;
    @Autowired PointsLogRepository pointLogs;
    @Autowired PlatformTransactionManager transactions;
    Product product;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE verified_payment_events,card_keys,order_items,orders,products,payment_channels,users,points_logs,site_configs CASCADE");
        product = product();
        PaymentChannel c = new PaymentChannel(); c.setChannelCode("alipay"); c.setChannelName("Alipay"); channels.save(c);
    }
    Product product() {
        Product p = new Product(); p.setTitle("Inventory test"); p.setCategoryId(UUID.randomUUID()); p.setBasePrice(BigDecimal.TEN); return products.save(p);
    }
    CardKey stock(Product p) {
        CardKey k = new CardKey(); k.setProductId(p.getId()); k.setContent(UUID.randomUUID().toString()); return cards.save(k);
    }
    UUID pending(Product p, int quantity) {
        return new TransactionTemplate(transactions).execute(tx -> {
            Order o = new Order(); o.setOrderType(OrderType.DIRECT); o.setActualAmount(BigDecimal.TEN);
            o.setTotalAmount(BigDecimal.TEN); o.setExpiresAt(LocalDateTime.now().plusMinutes(15)); o.setSessionToken("test-session"); orders.saveAndFlush(o);
            line(o,p,quantity); return o.getId();
        });
    }
    void line(Order o, Product p, int quantity) {
        OrderItem i = new OrderItem(); i.setOrderId(o.getId()); i.setProductId(p.getId()); i.setProductTitle(p.getTitle());
        i.setQuantity(quantity); i.setUnitPrice(BigDecimal.TEN); i.setSubtotal(BigDecimal.TEN); items.save(i);
    }
    Map<String,Object> request(String key) {
        Map<String,Object> req = new HashMap<>(Map.of("product_id",product.getId().toString(),"quantity",1,"payment_method","alipay"));
        if (key != null) req.put("idempotency_key",key);
        req.put("quote_token",orderService.quote(req,null,"test-session").quoteToken()); return req;
    }
    <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            var tasks = List.of(a,b).stream().map(action -> pool.submit(() -> { ready.countDown(); if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException(); return action.call(); })).toList();
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<T> results = new ArrayList<>(); for (var future : tasks) results.add(future.get(20,TimeUnit.SECONDS)); return results;
        }
    }
    @Test void lastCardHasOnlyOneSuccessfulOrder() throws Exception {
        stock(product); var a=request(null); var b=request(null);
        Callable<Boolean> buyA=() -> { try { orderService.createDirectOrder(a,null,null,"test-session"); return true; } catch (BusinessException e) { return false; } };
        Callable<Boolean> buyB=() -> { try { orderService.createDirectOrder(b,null,null,"test-session"); return true; } catch (BusinessException e) { return false; } };
        assertThat(parallel(buyA,buyB)).containsExactlyInAnyOrder(true,false);
        assertThat(orders.count()).isEqualTo(1); assertThat(cards.findAll()).allMatch(k -> k.getStatus()==CardKeyStatus.LOCKED);
    }
    @Test void concurrentIdempotencyKeyReservesOnce() throws Exception {
        stock(product); var a=request("same-request");
        Callable<UUID> buy=() -> orderService.createDirectOrder(a,null,null,"test-session").orderId();
        var ids=parallel(buy,buy); assertThat(ids.get(0)).isEqualTo(ids.get(1)); assertThat(orders.count()).isEqualTo(1);
    }
    @Test void multiItemFailureRollsBackOrderAndReservations() {
        CardKey k=stock(product); Product other=product();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            Order o=new Order(); o.setOrderType(OrderType.CART); o.setExpiresAt(LocalDateTime.now().plusMinutes(15)); orders.saveAndFlush(o);
            line(o,product,1); line(o,other,1); inventory.reserve(o.getId()); return null;
        })).isInstanceOf(BusinessException.class);
        assertThat(orders.count()).isZero(); assertThat(cards.findById(k.getId()).orElseThrow().getStatus()).isEqualTo(CardKeyStatus.AVAILABLE);
    }
    @Test void duplicatePaymentAndDeliveryNeverAllocateAgain() {
        stock(product); UUID id=pending(product,1); inventory.reserve(id);
        inventory.confirmPayment(id,null); inventory.confirmPayment(id,null);
        delivery.deliverOrders(Map.of("order_ids",List.of(id.toString())),"test-session");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(cards.findByOrderId(id)).hasSize(1).allMatch(k -> k.getStatus()==CardKeyStatus.SOLD);
    }
    @Test void closeReleasesOnce() {
        CardKey k=stock(product); UUID id=pending(product,1); inventory.reserve(id);
        inventory.close(id,true); inventory.close(id,true);
        CardKey stored=cards.findById(k.getId()).orElseThrow(); assertThat(stored.getStatus()).isEqualTo(CardKeyStatus.AVAILABLE); assertThat(stored.getOrderId()).isNull();
    }
    @Test void paidAndTimeoutRaceNeverReturnsSoldCardToInventory() throws Exception {
        stock(product); UUID id=pending(product,1); inventory.reserve(id);
        jdbc.update("update orders set expires_at = ? where id = ?",LocalDateTime.now().minusMinutes(1),id);
        parallel(() -> { inventory.confirmPayment(id,null); return true; },() -> { inventory.close(id,false); return true; });
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(cards.findByOrderId(id)).hasSize(1).allMatch(k -> k.getStatus()==CardKeyStatus.SOLD);
    }
    @Test void latePaymentWaitsForRestockThenDeliversOnce() {
        UUID id=pending(product,1); inventory.close(id,true); inventory.confirmPayment(id,null);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID_REVIEW);
        assertThat(orders.findById(id).orElseThrow().getPaidAt()).isNotNull();
        stock(product); inventory.retryDelivery(id); inventory.confirmPayment(id,null);
        assertThat(cards.findByOrderId(id)).hasSize(1); assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }
    @Test void refundIsTerminalEvenWhenPaymentCallbackRepeats() {
        UUID id=pending(product,1); inventory.confirmPayment(id,null); inventory.recordRefund(id,"refund-test-001");
        stock(product); inventory.confirmPayment(id,null);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED); assertThat(cards.findByOrderId(id)).isEmpty();
        assertThatThrownBy(() -> inventory.retryDelivery(id)).isInstanceOf(BusinessException.class);
    }
    @Test void reservedKeysCannotBeExportedOrInvalidated() {
        CardKey k=stock(product); UUID id=pending(product,1); inventory.reserve(id);
        assertThatThrownBy(() -> delivery.exportCardKeys(id,"test-session")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adminCards.invalidateCardKey(k.getId())).isInstanceOf(BusinessException.class);
    }
    @Test void delayedGatewayResponseCannotOverwritePaidStatus() {
        stock(product); UUID id=pending(product,1); inventory.reserve(id);
        Order snapshot=orders.findById(id).orElseThrow(); inventory.confirmPayment(id,null);
        snapshot.setPaymentUrl("https://example.invalid/pay"); inventory.storePaymentDetails(snapshot);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }
    @Test void duplicateCartLinesReserveDistinctCards() {
        stock(product); stock(product); UUID id=pending(product,1);
        new TransactionTemplate(transactions).execute(tx -> { line(orders.findById(id).orElseThrow(),product,1); return null; });
        inventory.reserve(id);
        var keys=cards.findByOrderId(id); assertThat(keys).hasSize(2);
        assertThat(keys.stream().map(CardKey::getOrderItemId).distinct().count()).isEqualTo(2);
        inventory.confirmPayment(id,null); assertThat(cards.findByOrderId(id)).allMatch(k -> k.getStatus()==CardKeyStatus.SOLD);
    }
    @Test void duplicatePaymentAwardsPointsOnlyOnce() {
        User u=new User(); u.setUsername("buyer"); u.setEmail("buyer@example.invalid"); u.setPasswordHash("unused-test-hash"); users.save(u);
        for (var entry : Map.of("points_enabled","true","points_rate","1").entrySet()) {
            SiteConfig c=new SiteConfig(); c.setConfigKey(entry.getKey()); c.setConfigValue(entry.getValue()); configs.save(c);
        }
        stock(product); UUID id=pending(product,1); jdbc.update("update orders set user_id = ? where id = ?",u.getId(),id);
        inventory.reserve(id); inventory.confirmPayment(id,null); inventory.confirmPayment(id,null);
        assertThat(users.findById(u.getId()).orElseThrow().getPoints()).isEqualTo(10);
        assertThat(pointLogs.count()).isEqualTo(1);
    }
}
