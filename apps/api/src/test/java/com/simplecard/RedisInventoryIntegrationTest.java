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
@EnabledIfEnvironmentVariable(named="REDIS_COORDINATION_TESTS", matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55439/inventory_test",
    "spring.datasource.username=inventory_test", "spring.datasource.password=inventory_test",
    "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
    "coordination.enabled=true", "coordination.address=redis://127.0.0.1:56379", "coordination.password=",
    "coordination.prefix=inventory-redis-test", "coordination.wait-ms=200", "coordination.watchdog-ms=2000",
    "spring.sql.init.mode=never", "spring.mail.host=localhost", "mail.enabled=false"})
class RedisInventoryIntegrationTest {
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
    @Autowired RedisOrderCoordinator coordination;
    @Autowired PaymentInboxService inbox;
    @Autowired VerifiedPaymentEventRepository events;
    @Autowired org.redisson.api.RedissonClient redis;
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
        assertThatThrownBy(() -> coordination.execute(List.of(coordination.stockKey(product.getId(),null),coordination.stockKey(other.getId(),null)), () -> new TransactionTemplate(transactions).execute(tx -> {
            Order o=new Order(); o.setOrderType(OrderType.CART); o.setExpiresAt(LocalDateTime.now().plusMinutes(15)); orders.saveAndFlush(o);
            line(o,product,1); line(o,other,1); inventory.reserve(o.getId()); return null;
        }))).isInstanceOf(BusinessException.class);
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

    private org.redisson.api.RedissonClient anotherClient() {
        org.redisson.config.Config c=new org.redisson.config.Config();c.setThreads(2).setNettyThreads(2).setLockWatchdogTimeout(2000);
        c.useSingleServer().setAddress("redis://127.0.0.1:56379").setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        return org.redisson.Redisson.create(c);
    }
    @Test void durableReceiptSurvivesLockContentionAndPreventsClosing() {
        stock(product);UUID id=pending(product,1);inventory.reserve(id);
        var other=anotherClient();var blocker=other.getLock(coordination.orderKey(id));blocker.lock();
        try {
            inbox.accept("contention-event","test",id,null);
            var event=events.findByEventKey("contention-event").orElseThrow();assertThat(event.isProcessed()).isFalse();assertThat(event.getRetryCount()).isEqualTo(1);
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        } finally { blocker.unlock();other.shutdown(); }
        inventory.close(id,true);assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        inbox.process(events.findByEventKey("contention-event").orElseThrow().getId());
        inbox.accept("contention-event","test",id,null);
        assertThat(events.count()).isEqualTo(1);assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }
    @Test void redisOutageRetainsVerifiedPaymentAndRecoveryFinishesIt() throws Exception {
        stock(product);UUID id=pending(product,1);inventory.reserve(id);
        Process pause=new ProcessBuilder("docker","pause","simplecard-redis-integration").redirectErrorStream(true).start();
        assertThat(pause.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(pause.exitValue()).isZero();
        try {
            inbox.accept("outage-event","test",id,null);
            var event=events.findByEventKey("outage-event").orElseThrow();assertThat(event.isProcessed()).isFalse();
            assertThat(cards.findByOrderId(id)).allMatch(k->k.getStatus()==CardKeyStatus.LOCKED);
        } finally {
            Process resume=new ProcessBuilder("docker","unpause","simplecard-redis-integration").redirectErrorStream(true).start();
            assertThat(resume.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(resume.exitValue()).isZero();
        }
        assertThat(inbox.process(events.findByEventKey("outage-event").orElseThrow().getId())).isTrue();
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }
    @Test void transactionCommitOccursBeforeRedisUnlock() throws Exception {
        stock(product);UUID id=pending(product,1);inventory.reserve(id);
        CountDownLatch beforeCommit=new CountDownLatch(1),allowCommit=new CountDownLatch(1);
        var other=anotherClient();
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(()-> {
                try { coordination.execute(List.of(coordination.orderKey(id),coordination.stockKey(product.getId(),null)),()->new TransactionTemplate(transactions).execute(tx->{
                    inventory.confirmPayment(id,null);
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void beforeCommit(boolean readOnly){beforeCommit.countDown();try{if(!allowCommit.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                    });return null;
                }));return true;} catch(Throwable t){throw new RuntimeException(t);}
            });
            try { assertThat(beforeCommit.await(10,TimeUnit.SECONDS)).isTrue();assertThat(other.getLock(coordination.orderKey(id)).tryLock()).isFalse(); }
            finally {allowCommit.countDown();}
            assertThat(future.get(10,TimeUnit.SECONDS)).isTrue();
            var lock=other.getLock(coordination.orderKey(id));assertThat(lock.tryLock(2,TimeUnit.SECONDS)).isTrue();lock.unlock();
        } finally {other.shutdown();}
    }
    @Test void watchdogRenewsAndExpiredOwnerCannotUnlockNewOwner() throws Exception {
        var other=anotherClient();
        try {
            var renewed=redis.getLock("inventory-redis-test:watchdog");renewed.lock();
            try {Thread.sleep(2600);assertThat(other.getLock("inventory-redis-test:watchdog").tryLock()).isFalse();}finally{renewed.unlock();}
            var old=redis.getLock("inventory-redis-test:expired-owner");old.tryLock(0,200,TimeUnit.MILLISECONDS);Thread.sleep(350);
            var current=other.getLock("inventory-redis-test:expired-owner");assertThat(current.tryLock()).isTrue();
            try {assertThatThrownBy(old::unlock).isInstanceOf(IllegalMonitorStateException.class);assertThat(current.isHeldByCurrentThread()).isTrue();}finally{current.unlock();}
        } finally {other.shutdown();}
    }
    @Test void separateJvmsCoordinatePaymentAndClosing() throws Exception {
        stock(product);UUID id=pending(product,1);inventory.reserve(id);
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"redis-jvm-").toAbsolutePath();
        var gate=dir.resolve("go");var paid=dir.resolve("paid");var closed=dir.resolve("closed");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String javaExe=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Process a=new ProcessBuilder(javaExe,"-Xmx256m","-cp",cp,"com.simplecard.RedisInventoryProcessWorker","pay",id.toString(),gate.toString(),paid.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("pay.log").toFile()).start();
        Process b=new ProcessBuilder(javaExe,"-Xmx256m","-cp",cp,"com.simplecard.RedisInventoryProcessWorker","close",id.toString(),gate.toString(),closed.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("close.log").toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while((!java.nio.file.Files.exists(dir.resolve("paid.ready")) || !java.nio.file.Files.exists(dir.resolve("closed.ready"))) && System.nanoTime()<deadline && a.isAlive() && b.isAlive())Thread.sleep(100);
            assertThat(java.nio.file.Files.exists(dir.resolve("paid.ready"))).withFailMessage("Pay worker log: %s",java.nio.file.Files.readString(dir.resolve("pay.log"))).isTrue();
            assertThat(java.nio.file.Files.exists(dir.resolve("closed.ready"))).withFailMessage("Close worker log: %s",java.nio.file.Files.readString(dir.resolve("close.log"))).isTrue();
            java.nio.file.Files.writeString(gate,"go");
            assertThat(a.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(b.waitFor(30,TimeUnit.SECONDS)).isTrue();
            assertThat(java.nio.file.Files.readString(paid)).isEqualTo("OK");assertThat(java.nio.file.Files.readString(closed)).isEqualTo("OK");
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(cards.findByOrderId(id)).hasSize(1).allMatch(k->k.getStatus()==CardKeyStatus.SOLD);
        } finally {a.destroyForcibly();b.destroyForcibly();}
    }

    @Test void databaseGuardsRemainCorrectAfterRedisOwnershipIsLost() throws Exception {
        stock(product);UUID id=pending(product,1);inventory.reserve(id);
        CountDownLatch beforeCommit=new CountDownLatch(1),allowCommit=new CountDownLatch(1);
        var other=anotherClient();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{
                try {coordination.execute(List.of(coordination.orderKey(id),coordination.stockKey(product.getId(),null)),()->new TransactionTemplate(transactions).execute(tx->{
                    inventory.confirmPayment(id,null);
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                        @Override public void beforeCommit(boolean readOnly){beforeCommit.countDown();try{if(!allowCommit.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){throw new IllegalStateException(e);}}
                    });return null;
                }));return true;}catch(Throwable t){throw new RuntimeException(t);}
            });
            try {
                assertThat(beforeCommit.await(10,TimeUnit.SECONDS)).isTrue();
                // Fault injection: simulate Redis losing both lock records during a transaction.
                other.getLock(coordination.orderKey(id)).forceUnlock();other.getLock(coordination.stockKey(product.getId(),null)).forceUnlock();
                var second=pool.submit(()->inventory.confirmPayment(id,null));Thread.sleep(150);
                assertThat(second.isDone()).isFalse();allowCommit.countDown();
                assertThat(first.get(10,TimeUnit.SECONDS)).isTrue();second.get(10,TimeUnit.SECONDS);
            } finally {allowCommit.countDown();}
            assertThat(cards.findByOrderId(id)).hasSize(1).allMatch(k->k.getStatus()==CardKeyStatus.SOLD);
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        }finally{other.shutdown();}
    }

    @Test void crashedProcessReleasesItsLocksByLeaseExpiry() throws Exception {
        stock(product);UUID id=pending(product,1);inventory.reserve(id);
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"redis-crash-").toAbsolutePath();
        var gate=dir.resolve("go");var result=dir.resolve("holder");java.nio.file.Files.writeString(gate,"go");
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String javaExe=java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Process holder=new ProcessBuilder(javaExe,"-Xmx256m","-cp",cp,"com.simplecard.RedisInventoryProcessWorker","hold",id.toString(),gate.toString(),result.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("holder.log").toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(!java.nio.file.Files.exists(dir.resolve("holder.held")) && holder.isAlive() && System.nanoTime()<deadline)Thread.sleep(100);
            assertThat(java.nio.file.Files.exists(dir.resolve("holder.held"))).withFailMessage("Worker log: %s",java.nio.file.Files.readString(dir.resolve("holder.log"))).isTrue();
            var lock=redis.getLock(coordination.orderKey(id));assertThat(lock.tryLock()).isFalse();
            holder.destroyForcibly();assertThat(holder.waitFor(10,TimeUnit.SECONDS)).isTrue();
            assertThat(lock.tryLock(6,TimeUnit.SECONDS)).isTrue();lock.unlock();
            inventory.confirmPayment(id,null);
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        }finally{holder.destroyForcibly();}
    }
}
