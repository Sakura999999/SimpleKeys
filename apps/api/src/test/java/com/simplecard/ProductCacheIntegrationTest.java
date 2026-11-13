package com.simplecard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.config.ProductGuardProperties;
import com.simplecard.constant.*;
import com.simplecard.entity.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import com.simplecard.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.mail.host=localhost",
        "spring.datasource.url=jdbc:h2:mem:product_cache;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.properties.hibernate.generate_statistics=true", "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "product-guard.enabled=true", "product-guard.address=redis://127.0.0.1:56380", "product-guard.password=",
        "product-guard.prefix=product-cache-integration", "product-guard.initial-delay-ms=3600000",
        "product-guard.database-per-second=10000", "product-guard.global-per-second=10000", "product-guard.ip-per-second=10000",
        "coordination.enabled=true", "coordination.address=redis://127.0.0.1:56381", "coordination.password="})
@EnabledIfEnvironmentVariable(named="PRODUCT_GUARD_TESTS",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCacheIntegrationTest {
    private static final String ROOT="product-cache-integration:{product-guard}:";
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        if ("true".equals(System.getenv("PRODUCT_GUARD_POSTGRES_TESTS"))) {
            r.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:55440/product_guard_test");
            r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
            r.add("spring.datasource.username",()->"guard_test"); r.add("spring.datasource.password",()->"guard_test_only");
        }
    }
    @Autowired ProductService service;
    @Autowired ProductQueryProtection guard;
    @Autowired ProductDisplayCache cache;
    @Autowired ProductProtectionStore store;
    @Autowired ProductGuardProperties p;
    @MockitoSpyBean ProductRepository products;
    @Autowired ProductSpecRepository specs;
    @Autowired WholesaleRuleRepository rules;
    @Autowired CardKeyRepository cards;
    @Autowired EntityManagerFactory emf;
    @Autowired ObjectMapper json;
    @Autowired RedisOrderCoordinator coordinator;
    @Autowired @Qualifier("productGuardRedis") RedissonClient redis;
    @Autowired @Qualifier("coordinationRedis") RedissonClient orders;

    @BeforeEach void reset() {
        redis.getKeys().deleteByPattern("product-cache-integration:*");
        cards.deleteAllInBatch(); specs.deleteAllInBatch(); rules.deleteAllInBatch(); products.deleteAllInBatch();
        p.setDisplayMaxEntries(2000); p.setDisplayMaxBytes(33554432); p.setDisplayMaxEntryBytes(16384);
        p.setGlobalPerSecond(10000); p.setIpPerSecond(10000); p.setDatabasePerSecond(10000);
    }
    Product product() {
        Product value=new Product(); value.setTitle("Original"); value.setDetailMd("Public body");
        value.setCategoryId(UUID.randomUUID()); value.setBasePrice(BigDecimal.TEN); return products.saveAndFlush(value);
    }
    Map<String,Object> display(String title) { return new ProductDisplayFields(title,null,null,null,"body",null,null).asMap(); }
    Map<String,Object> scoped(Product product, ProductDisplayCache target, String context, java.util.function.Supplier<Map<String,Object>> loader) {
        return guard.load(product.getId(),()->Optional.of(product),ignored->target.get(product.getId(),context,loader));
    }
    @Test void warmCacheKeepsPriceAndVisibilityLiveAndCachesOnlyAllowlistedText() {
        Product product=product(); guard.rebuild(); UUID id=product.getId();
        assertThat(service.getProductDetail(id)).containsEntry("title","Original");
        assertThat(service.getProductDetail(id)).containsEntry("title","Original");
        verify(products,times(1)).displayFields(id);
        String encoded=store.readDisplay(id,cache.context(id));
        assertThat(encoded).contains("title").doesNotContain("base_price","stock_available","specs","wholesale_rules");
        service.updateProduct(id,Map.of("base_price",19,"title","Changed"));
        assertThat(service.getProductDetail(id)).containsEntry("title","Changed")
                .containsEntry("base_price",new BigDecimal("19.00"));
        service.updateProduct(id,Map.of("is_enabled",false));
        assertThatThrownBy(()->service.getProductDetail(id)).isInstanceOfSatisfying(BusinessException.class,
                e->assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        service.updateProduct(id,Map.of("is_enabled",true)); guard.rebuild(); service.getProductDetail(id);
        service.deleteProduct(id);
        assertThatThrownBy(()->service.getProductDetail(id)).isInstanceOf(BusinessException.class);
    }
    @Test void specPricesWholesaleAndStockNeverComeFromDisplayCache() {
        Product product=product(); UUID id=product.getId(); service.updateProduct(id,Map.of("spec_enabled",true));
        service.createSpec(id,Map.of("name","Visible","price",12));
        ProductSpec spec=specs.findByProductIdAndIsDeletedOrderBySortOrderAsc(id,0).getFirst();
        addCard(id,null,CardKeyStatus.AVAILABLE); addCard(id,spec.getId(),CardKeyStatus.AVAILABLE);
        addCard(id,spec.getId(),CardKeyStatus.LOCKED); addCard(id,spec.getId(),CardKeyStatus.SOLD);
        addCard(id,spec.getId(),CardKeyStatus.INVALID);
        guard.rebuild(); Map<String,Object> detail=service.getProductDetail(id);
        assertThat(detail).containsEntry("stock_available",1L);
        Map<?,?> value=(Map<?,?>)((List<?>)detail.get("specs")).getFirst();
        assertThat(value.get("card_key_count")).isEqualTo(3L);
        service.updateSpec(id,spec.getId(),Map.of("price",23));
        service.setWholesaleRules(id,Map.of("rules",List.of(Map.of("min_quantity",5,"unit_price",8))));
        detail=service.getProductDetail(id);
        assertThat(((Map<?,?>)((List<?>)detail.get("specs")).getFirst()).get("price")).isEqualTo(new BigDecimal("23.00"));
        assertThat(((Map<?,?>)((List<?>)detail.get("wholesale_rules")).getFirst()).get("unit_price")).isEqualTo(new BigDecimal("8.00"));
        service.updateSpec(id,spec.getId(),Map.of("is_visible",false));
        detail=service.getProductDetail(id);
        assertThat((List<?>)detail.get("specs")).isEmpty(); assertThat(detail).containsEntry("stock_available",0L).containsEntry("has_specs",true);
    }
    private void addCard(UUID id,UUID spec,CardKeyStatus status) {
        CardKey card=new CardKey(); card.setProductId(id); card.setSpecId(spec); card.setStatus(status);
        card.setContent("isolated-test-card-"+UUID.randomUUID()); cards.saveAndFlush(card);
    }
    @Test void warmDetailSqlDoesNotGrowWithTwentySpecs() {
        Product product=product(); UUID id=product.getId(); service.updateProduct(id,Map.of("spec_enabled",true));
        for (int i=0;i<20;i++) service.createSpec(id,Map.of("name","Spec "+i,"price",i+1));
        guard.rebuild(); service.getProductDetail(id);
        var stats=emf.unwrap(SessionFactory.class).getStatistics(); stats.clear();
        service.getProductDetail(id);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(5);
    }
    @Test void oldFillCannotSurvivePublicationOrFilterVersionSwitch() {
        Product product=product(); guard.rebuild(); UUID id=product.getId(); String old=cache.context(id);
        service.updateProduct(id,Map.of("title","New")); guard.rebuild();
        assertThat(store.writeDisplay(id,old,"{}",360)).isZero();
        assertThat(store.readDisplay(id,old)).isNull();
        assertThat(service.getProductDetail(id)).containsEntry("title","New");
    }
    @Test void routineBloomRebuildDoesNotInvalidateAllDisplayEntries() {
        Product product=product(); guard.rebuild(); String context=cache.context(product.getId()); service.getProductDetail(product.getId());
        guard.rebuild();
        assertThat(cache.context(product.getId())).isEqualTo(context);
        assertThat(store.readDisplay(product.getId(),context)).isNotNull();
        service.getProductDetail(product.getId()); verify(products,times(1)).displayFields(product.getId());
    }
    @Test void concurrentColdReadsAcrossTwoClientsRebuildOnce() throws Exception {
        Product product=product(); guard.rebuild(); String context=cache.context(product.getId());
        Config config=new Config(); config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:56380").setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        RedissonClient other=Redisson.create(config);
        ExecutorService pool=Executors.newFixedThreadPool(6);
        try {
            DefaultListableBeanFactory factory=new DefaultListableBeanFactory(); factory.registerSingleton("cache",other);
            ProductDisplayCache second=new ProductDisplayCache(new ProductProtectionStore(factory.getBeanProvider(RedissonClient.class),p),p,guard,json);
            assertThat(second.context(product.getId())).isEqualTo(context);
            long oldFailures=cache.failures.sum(), oldRejected=cache.rejected.sum();
            CountDownLatch ready=new CountDownLatch(6),start=new CountDownLatch(1);
            AtomicInteger builds=new AtomicInteger(); List<Future<Map<String,Object>>> calls=new ArrayList<>();
            for (int i=0;i<6;i++) {
                ProductDisplayCache target=i%2==0?cache:second;
                calls.add(pool.submit(()->{ ready.countDown(); start.await(); return scoped(product,target,context,()->{
                    assertThat(redis.getBucket(ROOT+"display-lock:"+product.getId()).isExists()).isTrue();
                    assertThat(orders.getBucket(ROOT+"display-lock:"+product.getId()).isExists()).isFalse();
                    builds.incrementAndGet(); try { Thread.sleep(25); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    return display("Coalesced");
                }); }));
            }
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            int successful=0;
            for (var call:calls) {
                try { assertThat(call.get(5,TimeUnit.SECONDS)).containsEntry("title","Coalesced"); successful++; }
                catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(BusinessException.class,
                            failure->assertThat(failure.getHttpStatus().value()).isEqualTo(503));
                }
            }
            assertThat(successful).isGreaterThan(0);
            assertThat(scoped(product,second,context,()->{ builds.incrementAndGet(); return display("unexpected"); }))
                    .containsEntry("title","Coalesced");
            assertThat(builds).as("first failures=%s, rejects=%s, second failures=%s, rejects=%s",cache.failures.sum()-oldFailures,
                    cache.rejected.sum()-oldRejected,second.failures.sum(),second.rejected.sum()).hasValue(1);
            assertThat(orders.getBucket(ROOT+"display-lock:"+product.getId()).isExists()).isFalse();
        } finally { pool.shutdownNow(); other.shutdown(); }
    }
    @Test void expiredEntryIsRebuiltAndRandomTtlsHaveBounds() {
        Product product=product(); guard.rebuild(); UUID id=product.getId(); String context=cache.context(id);
        service.getProductDetail(id);
        long now=System.currentTimeMillis();
        double expires=redis.getScoredSortedSet(ROOT+"display-expiry",StringCodec.INSTANCE).getScore(id.toString());
        assertThat(expires-now).isBetween(295000.0,421000.0);
        redis.getScoredSortedSet(ROOT+"display-expiry",StringCodec.INSTANCE).add(now-1,id.toString());
        service.getProductDetail(id); verify(products,times(2)).displayFields(id);
    }
    @Test void globalEntryAndByteQuotasDoNotTurnSuccessfulReadsIntoErrors() throws Exception {
        Product first=product(),second=product(); guard.rebuild(); p.setDisplayMaxEntries(1);
        assertThat(service.getProductDetail(first.getId())).containsEntry("title","Original");
        assertThat(service.getProductDetail(second.getId())).containsEntry("title","Original");
        assertThat(redis.getMap(ROOT+"display-values",StringCodec.INSTANCE).size()).isEqualTo(1);
        p.setDisplayMaxBytes(1);
        assertThat(store.writeDisplay(second.getId(),cache.context(second.getId()),json.writeValueAsString(display("x")),360)).isEqualTo(-1);
    }
    @Test void oversizedTextBypassesCachingAndStillReturnsNormally() {
        Product product=product(); product.setDetailMd("x".repeat(17000)); products.saveAndFlush(product); guard.rebuild();
        long before=cache.rejectedBySize.sum();
        assertThat(service.getProductDetail(product.getId()).get("detail_md")).isEqualTo(product.getDetailMd());
        assertThat(cache.rejectedBySize.sum()).isEqualTo(before+1);
        assertThat(store.readDisplay(product.getId(),cache.context(product.getId()))).isNull();
    }
    @Test void full128MbRedisPreservesReadGatesAndRefusesDisplayGrowth() throws Exception {
        Product product=product(); guard.rebuild(); service.getProductDetail(product.getId());
        assertThat(store.displayMemory().get(1)).isEqualTo(128L*1024*1024);
        String snapshot=store.snapshot(),context=cache.context(product.getId());
        String next=store.runId()+"|"+UUID.randomUUID();
        store.bloom(next).tryInit(10000,0.001); store.bloom(next).add(product.getId().toString()); store.claimBuild("oom-publisher");
        var fill=redis.<String,String>getMap(ROOT+"oom-fill",StringCodec.INSTANCE);
        boolean full=false;
        try {
            String chunk="x".repeat(1024*1024);
            for (int i=0;i<160;i++) {
                try { fill.put(Integer.toString(i),chunk); }
                catch (RuntimeException e) { assertThat(ProductProtectionStore.capacityError(e)).isTrue(); full=true; break; }
            }
            assertThat(full).isTrue();
            assertThat(store.snapshot()).isEqualTo(snapshot);
            assertThat(store.readDisplay(product.getId(),context)).isNotNull();
            assertThat(store.writeDisplay(product.getId(),context,json.writeValueAsString(display("refused")),360)).isEqualTo(-3);
            UUID absent=UUID.randomUUID(); AtomicInteger queries=new AtomicInteger();
            assertThatThrownBy(()->guard.load(absent,()->{ queries.incrementAndGet();return Optional.empty(); },Product::getId))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
            assertThat(queries).hasValue(0);
            store.pruneDisplay();
            try { store.publish(next,"oom-publisher",Map.of()); }
            catch (RuntimeException e) { assertThat(ProductProtectionStore.capacityError(e)).isTrue(); }
            String active=store.snapshot();
            assertThat(active).isIn(snapshot,next);
            assertThat(store.bloom(active).contains(product.getId().toString())).isTrue();
            store.retireIfUnpublished(active);
            assertThat(redis.getBucket(store.bloom(active).getName()).remainTimeToLive()).isEqualTo(-1);
            coordinator.execute(List.of(ROOT+"oom-order-lock"),()->{
                assertThat(orders.getLock(ROOT+"oom-order-lock").isHeldByCurrentThread()).isTrue();return null;
            });
        } catch (Throwable e) { if (e instanceof Exception exception) throw exception; throw new AssertionError(e); }
        finally { fill.delete(); }
    }
    @Test void defaultGlobalIpAndDatabaseBudgetsAreEnforced() {
        Product product=product(); guard.rebuild(); p.setGlobalPerSecond(300); p.setIpPerSecond(30); p.setDatabasePerSecond(100);
        seedWindow(ROOT+"rate:http",300);
        assertThatThrownBy(()->guard.admit("global-peer")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(429));
        redis.getKeys().delete(ROOT+"rate:http");
        seedWindow(ROOT+"rate:http:"+Integer.toHexString("peer".hashCode()),30);
        assertThatThrownBy(()->guard.admit("peer")).isInstanceOf(BusinessException.class);
        seedWindow(ROOT+"rate:database",100);
        assertThatThrownBy(()->guard.load(product.getId(),()->Optional.of(product),Product::getId))
                .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(503));
    }
    @Test void delayedOldLoaderCannotOverwriteEditedContent() throws Exception {
        Product product=product(); guard.rebuild(); String context=cache.context(product.getId());
        CountDownLatch loading=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> old=worker.submit(()->scoped(product,cache,context,()->{
                loading.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return display("Old text");
            }));
            assertThat(loading.await(5,TimeUnit.SECONDS)).isTrue();
            service.updateProduct(product.getId(),Map.of("title","Fresh text")); guard.rebuild();
            release.countDown(); old.get(5,TimeUnit.SECONDS);
            assertThat(store.readDisplay(product.getId(),cache.context(product.getId()))).isNull();
            assertThat(service.getProductDetail(product.getId())).containsEntry("title","Fresh text");
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void lockContentionHasBoundedWaitAndDoesNotIndependentlyFallThroughToSql() throws Exception {
        Product product=product(); guard.rebuild(); String context=cache.context(product.getId());
        CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> owner=worker.submit(()->{
                RLock lock=redis.getLock(ROOT+"display-lock:"+product.getId()); lock.lock();
                try { locked.countDown(); release.await(5,TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
            });
            assertThat(locked.await(5,TimeUnit.SECONDS)).isTrue();
            AtomicInteger loads=new AtomicInteger(); long begin=System.nanoTime();
            assertThatThrownBy(()->scoped(product,cache,context,()->{ loads.incrementAndGet(); return display("unexpected"); }))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(503));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)).isLessThan(2000);
            assertThat(loads).hasValue(0);
            release.countDown(); owner.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void manyDifferentExpiredEntriesShareTheSameEightDatabaseSlots() throws Exception {
        List<Product> batch=new ArrayList<>(); for (int i=0;i<16;i++) batch.add(product()); guard.rebuild();
        Map<UUID,String> contexts=new HashMap<>();
        for (Product product:batch) { contexts.put(product.getId(),cache.context(product.getId())); service.getProductDetail(product.getId()); }
        long past=System.currentTimeMillis()-1;
        for (Product product:batch) redis.getScoredSortedSet(ROOT+"display-expiry",StringCodec.INSTANCE).add(past,product.getId().toString());
        CountDownLatch loading=new CountDownLatch(8),rejected=new CountDownLatch(8),release=new CountDownLatch(1);
        AtomicInteger builds=new AtomicInteger(); ExecutorService workers=Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> calls=new ArrayList<>();
            for (Product product:batch) calls.add(workers.submit(()->{
                try {
                    scoped(product,cache,contexts.get(product.getId()),()->{
                        builds.incrementAndGet(); loading.countDown();
                        try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                        catch (InterruptedException e) { throw new RuntimeException(e); }
                        return display("Rebuilt");
                    }); return true;
                } catch (BusinessException e) { assertThat(e.getHttpStatus().value()).isEqualTo(503); rejected.countDown(); return false; }
            }));
            assertThat(loading.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(rejected.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(builds).hasValue(8); release.countDown();
            int succeeded=0; for (var call:calls) if (call.get(5,TimeUnit.SECONDS)) succeeded++;
            assertThat(succeeded).isEqualTo(8);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void productRedisOutageDoesNotBreakAnOrderLockOnTheOtherInstance() throws Throwable {
        Product product=product(); guard.rebuild();
        coordinator.execute(List.of(ROOT+"network-order-lock"),()->{
            docker("pause");
            try {
                assertThat(orders.getLock(ROOT+"network-order-lock").isHeldByCurrentThread()).isTrue();
                assertThat(guard.load(product.getId(),()->Optional.of(product),Product::getId)).isEqualTo(product.getId());
                assertThat(orders.getLock(ROOT+"network-order-lock").isHeldByCurrentThread()).isTrue();
            } finally { docker("unpause"); awaitRedis(); }
            return null;
        });
    }
    @Test void failedPublishAndUncertainCleanupDoNotExpireAnActiveFilter() {
        Product product=product(); guard.rebuild(); String active=store.snapshot();
        store.retireIfUnpublished(active);
        assertThat(redis.getBucket(store.bloom(active).getName()).remainTimeToLive()).isEqualTo(-1);
        store.claimBuild("invalid-owner");
        String next=store.runId()+"|"+UUID.randomUUID(); store.bloom(next).tryInit(10000,0.001); store.bloom(next).add(product.getId().toString());
        redis.getMap(ROOT+"completed",StringCodec.INSTANCE).put("bad-token",product.getId().toString());
        redis.getMap(ROOT+"dirty",StringCodec.INSTANCE).put(product.getId().toString(),"invalid-count");
        assertThat(store.publish(next,"invalid-owner",Map.of("bad-token",product.getId().toString()))).isFalse();
        assertThat(store.snapshot()).isEqualTo(active);
    }
    private void docker(String command) throws Exception {
        Process process=new ProcessBuilder("docker",command,"simplecard-product-guard-test").redirectErrorStream(true).start();
        assertThat(process.waitFor(20,TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
    }
    private void awaitRedis() throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { if (store.runId()!=null) return; }
            catch (RuntimeException e) { if (System.nanoTime()>=until) throw e; }
            Thread.sleep(50);
        }
    }
    private void seedWindow(String key,int count) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "local t=redis.call('TIME'); local n=t[1]*1000+math.floor(t[2]/1000); for i=1,tonumber(ARGV[1]) do redis.call('ZADD',KEYS[1],n,tostring(i)) end; redis.call('PEXPIRE',KEYS[1],2000);return 1",
                RScript.ReturnType.INTEGER,List.of(key),count);
    }
}
