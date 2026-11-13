package com.simplecard;

import com.simplecard.config.ProductGuardProperties;
import com.simplecard.constant.ErrorCode;
import com.simplecard.entity.Product;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.ProductRepository;
import com.simplecard.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Dedicated localhost:56380 Redis only. No developer/production DB or Redis is used. */
@SpringBootTest(properties = {
        "spring.mail.host=localhost", "spring.datasource.url=jdbc:h2:mem:product_guard;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "product-guard.enabled=true", "product-guard.address=redis://127.0.0.1:56380", "product-guard.password=",
        "coordination.enabled=true", "coordination.address=redis://127.0.0.1:56381", "coordination.password=",
        "product-guard.prefix=product-guard-integration", "product-guard.initial-delay-ms=3600000",
        "product-guard.database-per-second=10000", "product-guard.global-per-second=10000", "product-guard.ip-per-second=10000"
})
@EnabledIfEnvironmentVariable(named = "PRODUCT_GUARD_TESTS", matches = "true")
class ProductProtectionIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        if ("true".equals(System.getenv("PRODUCT_GUARD_POSTGRES_TESTS"))) {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:55440/product_guard_test");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> "guard_test");
            registry.add("spring.datasource.password", () -> "guard_test_only");
        }
    }
    @Autowired ProductQueryProtection guard;
    @Autowired ProductProtectionStore store;
    @MockitoSpyBean ProductRepository products;
    @Autowired ProductService service;
    @Autowired ProductGuardProperties properties;
    @Autowired PlatformTransactionManager transactions;
    @Autowired @Qualifier("productGuardRedis") RedissonClient redis;
    @Autowired @Qualifier("coordinationRedis") RedissonClient orderRedis;
    @Autowired RedisOrderCoordinator orderCoordinator;
    private static final String ROOT = "product-guard-integration:{product-guard}:";

    @BeforeEach void reset() {
        redis.getKeys().deleteByPattern("product-guard-integration:*");
        products.deleteAllInBatch();
        properties.setMaxNegativeEntries(5000);
    }
    Product product() {
        Product p = new Product(); p.setTitle("Guard test"); p.setBasePrice(BigDecimal.TEN);
        p.setCategoryId(UUID.randomUUID()); p.setEnabled(true);
        return products.saveAndFlush(p);
    }
    void missing(UUID id, AtomicInteger reads) {
        assertThatThrownBy(() -> guard.load(id, () -> { reads.incrementAndGet(); return products.findById(id); }, Product::getId))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test void randomIdsAreRejectedWithoutDatabaseAndRealProductIsVisible() {
        Product p = product(); guard.rebuild();
        AtomicInteger reads = new AtomicInteger();
        for (int i = 0; i < 300; i++) missing(UUID.randomUUID(), reads);
        assertThat(reads.get()).isLessThan(10); // probabilistic false positives are permitted
        assertThat(guard.load(p.getId(), () -> products.findById(p.getId()), Product::getId)).isEqualTo(p.getId());
    }
    @Test void falsePositiveFallsBackOnceThenUsesShortNegativeCache() {
        product(); guard.rebuild(); UUID id = UUID.randomUUID();
        String active = store.snapshot(); store.bloom(active).add(id.toString());
        AtomicInteger reads = new AtomicInteger(); missing(id, reads); missing(id, reads);
        assertThat(reads).hasValue(1);
        assertThat(store.negative(active, id)).isTrue();
    }
    @Test void negativeCacheIsBoundedAndLateFillCannotHidePublishedId() {
        product(); guard.rebuild(); String active = store.snapshot();
        properties.setMaxNegativeEntries(3);
        for (int i = 0; i < 50; i++) store.cacheNegative(active, UUID.randomUUID(), 60);
        String version = active.substring(active.indexOf('|') + 1);
        assertThat(redis.getScoredSortedSet(ROOT + "negative:" + version).size()).isEqualTo(3);
        Product p = product();
        store.beginMutation(p.getId(), "publish");
        store.cacheNegative(active, p.getId(), 60); // old query tries to fill after publication started
        assertThat(store.negative(active, p.getId())).isFalse();
        assertThat(guard.load(p.getId(), () -> products.findById(p.getId()), Product::getId)).isEqualTo(p.getId());
        store.completeMutation(p.getId(), "publish"); guard.rebuild();
        assertThat(store.dirty(p.getId())).isFalse();
        assertThat(store.canReject(active, p.getId())).isFalse(); // cannot combine old version with cleared marker
        assertThat(store.bloom(store.snapshot()).contains(p.getId().toString())).isTrue();
    }
    @Test void concurrentTransactionsDoNotClearEachOthersPendingMarker() {
        Product p = product(); guard.rebuild();
        store.beginMutation(p.getId(), "a"); store.beginMutation(p.getId(), "b");
        store.completeMutation(p.getId(), "b");
        String next = store.runId() + "|" + UUID.randomUUID();
        store.bloom(next).tryInit(10000, 0.001); store.bloom(next).add(p.getId().toString());
        store.claimBuild("cleanup-test");
        assertThat(store.publish(next, "cleanup-test", store.completedMutations())).isTrue();
        assertThat(store.dirty(p.getId())).isTrue();
        store.completeMutation(p.getId(), "a"); guard.rebuild();
        assertThat(store.dirty(p.getId())).isFalse();
    }
    @Test void unreadyOrPhysicallyMissingFilterNeverBlocksRealProduct() {
        Product p = product();
        assertThat(store.snapshot()).isNull();
        assertThat(guard.load(p.getId(), () -> products.findById(p.getId()), Product::getId)).isEqualTo(p.getId());
        guard.rebuild();
        redis.getBucket(store.bloom(store.snapshot()).getName()).delete(); // simulate missing bits with surviving metadata
        assertThat(guard.load(p.getId(), () -> products.findById(p.getId()), Product::getId)).isEqualTo(p.getId());
    }
    @Test void realServiceMutationMarksBeforeCommitAndRebuildIncludesNewProduct() {
        product(); guard.rebuild();
        Map<String,Object> result = service.createProduct(Map.of("title", "Published", "base_price", 12,
                "category_id", UUID.randomUUID().toString()));
        UUID id = (UUID) result.get("id");
        assertThat(store.dirty(id)).isTrue();
        assertThat(service.getProductDetail(id)).containsEntry("title", "Published");
        guard.rebuild();
        assertThat(store.dirty(id)).isFalse();
        service.updateProduct(id, Map.of("is_enabled", false));
        assertThatThrownBy(() -> service.getProductDetail(id)).isInstanceOf(BusinessException.class);
        service.updateProduct(id, Map.of("is_enabled", true));
        assertThat(service.getProductDetail(id)).containsEntry("title", "Published");
    }
    @Test void rolledBackPublicationIsHarmlessAndBuilderTokenFencesOldWorker() {
        product(); guard.rebuild(); String old = store.snapshot();
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(s -> { guard.mutation(id); s.setRollbackOnly(); });
        guard.rebuild(); assertThat(store.dirty(id)).isFalse();
        store.claimBuild("new-owner");
        assertThat(store.publish(old, "stale-owner", Map.of())).isFalse();
    }
    @Test void keysetRebuildReadsBeyondOneBatch() {
        List<Product> batch = new ArrayList<>();
        for (int i = 0; i < 505; i++) {
            Product p = new Product(); p.setTitle("Batch " + i); p.setBasePrice(BigDecimal.ONE);
            p.setCategoryId(UUID.randomUUID()); batch.add(p);
        }
        batch = products.saveAllAndFlush(batch); guard.rebuild(); String active = store.snapshot();
        for (Product p : batch) assertThat(store.bloom(active).contains(p.getId().toString())).isTrue();
    }
    @Test void slidingWindowsAreSharedAndPerPeerWithNoPreallocationOnRejectedPeers() {
        assertThat(store.admit("test", "a", 3, 2)).isTrue();
        assertThat(store.admit("test", "a", 3, 2)).isTrue();
        assertThat(store.admit("test", "a", 3, 2)).isFalse();
        assertThat(store.admit("test", "b", 3, 2)).isTrue();
        assertThat(store.admit("test", "c", 3, 2)).isFalse();
        assertThat(redis.getScoredSortedSet(ROOT + "rate:test:c").isExists()).isFalse();
    }
    @Test void databaseConcurrencyIsBoundedThroughDetailMapping() throws Exception {
        Product p = product(); guard.rebuild();
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<UUID>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(workers.submit(() -> guard.load(p.getId(), () -> Optional.of(p), v -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return v.getId();
            })));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> guard.load(p.getId(), () -> Optional.of(p), Product::getId))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getHttpStatus().value()).isEqualTo(503));
            release.countDown();
            for (Future<UUID> call : calls) assertThat(call.get()).isEqualTo(p.getId());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void failedRebuildKeepsServingPreviousVersion() {
        Product p = product(); guard.rebuild(); String previous = store.snapshot();
        doThrow(new IllegalStateException("injected DB scan failure")).when(products).firstIds(any());
        guard.rebuild();
        assertThat(store.snapshot()).isEqualTo(previous);
        assertThat(guard.load(p.getId(), () -> Optional.of(p), Product::getId)).isEqualTo(p.getId());
    }
    @Test void queriesAndPublicationContinueDuringRebuild() throws Exception {
        Product oldProduct = product(); guard.rebuild(); String previous = store.snapshot();
        CountDownLatch scanning = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            scanning.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return products.findAll(org.springframework.data.domain.PageRequest.of(0, 500,
                    org.springframework.data.domain.Sort.by("id"))).stream().map(Product::getId).toList();
        }).when(products).firstIds(any());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> rebuild = worker.submit(guard::rebuild);
            assertThat(scanning.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(store.snapshot()).isEqualTo(previous);
            assertThat(service.getProductDetail(oldProduct.getId())).containsEntry("id", oldProduct.getId());
            UUID id = (UUID) service.createProduct(Map.of("title", "During rebuild", "base_price", 2,
                    "category_id", UUID.randomUUID().toString())).get("id");
            assertThat(service.getProductDetail(id)).containsEntry("id", id);
            release.countDown(); rebuild.get(15, TimeUnit.SECONDS);
            assertThat(store.snapshot()).isNotEqualTo(previous);
            assertThat(service.getProductDetail(id)).containsEntry("id", id);
            assertThat(store.dirty(id)).isTrue(); // completion happened after build snapshot; retain protection
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test void redisOutageUsesBoundedFallbackAndRejectsPublicationWithoutCommitting() throws Exception {
        Product p = product(); guard.rebuild();
        docker("pause");
        try {
            int successful = 0, busy = 0;
            for (int i = 0; i < 50; i++) {
                try { assertThat(guard.load(p.getId(), () -> Optional.of(p), Product::getId)).isEqualTo(p.getId()); successful++; }
                catch (BusinessException e) { assertThat(e.getHttpStatus().value()).isEqualTo(503); busy++; }
            }
            assertThat(successful).isBetween(1, 20); assertThat(busy).isGreaterThan(0);
            assertThatThrownBy(() -> service.updateProduct(p.getId(), Map.of("title", "Must rollback")))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getHttpStatus().value()).isEqualTo(503));
            assertThat(products.findById(p.getId()).orElseThrow().getTitle()).isEqualTo("Guard test");
        } finally { docker("unpause"); awaitRedis(); }
    }
    @Test void redisRestartInvalidatesPersistedReadinessUntilRebuilt() throws Exception {
        Product p = product(); guard.rebuild(); String previous = store.snapshot();
        docker("restart"); awaitRedis();
        assertThat(store.snapshot()).isNull();
        assertThat(guard.load(p.getId(), () -> Optional.of(p), Product::getId)).isEqualTo(p.getId());
        guard.rebuild();
        assertThat(store.snapshot()).isNotNull().isNotEqualTo(previous);
    }
    @Test void abandonedPendingPublicationIsRecoveredUnderDatabaseBarrier() {
        product(); guard.rebuild(); UUID id = UUID.randomUUID();
        store.beginMutation(id, "process-crashed-before-callback");
        assertThat(store.dirty(id)).isTrue(); guard.rebuild();
        assertThat(store.dirty(id)).isFalse();
        // A rolled-back publication may remain a harmless Bloom false positive, never a false rejection.
        assertThat(store.bloom(store.snapshot()).contains(id.toString())).isTrue();
    }
    @Test void separateClientBeansDoNotRedirectOrderLocksToProductClient() throws Throwable {
        assertThat(orderRedis).isNotSameAs(redis);
        String key = ROOT + "order-client-injection-test";
        orderCoordinator.execute(List.of(key), () -> {
            assertThat(orderRedis.getLock(key).isHeldByCurrentThread()).isTrue();
            assertThat(redis.getLock(key).isHeldByCurrentThread()).isFalse();
            assertThat(redis.getBucket(key).isExists()).isFalse();
            return null;
        });
        assertThat(orderRedis.getLock(key).isLocked()).isFalse();
    }
    @Test void restartWithLostMarkersWaitsForInflightDatabasePublicationBeforeScanning() throws Exception {
        product(); guard.rebuild();
        CountDownLatch prepared = new CountDownLatch(1), commit = new CountDownLatch(1), scanning = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> publication = workers.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                guard.beforeMutation(); Product p = product(); guard.mutation(p.getId()); prepared.countDown();
                try { if (!commit.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return p.getId();
            }));
            assertThat(prepared.await(10, TimeUnit.SECONDS)).isTrue();
            docker("restart"); awaitRedis();
            // Simulate recovering an AOF that did not persist the publication's Redis marker.
            redis.getKeys().delete(ROOT + "pending", ROOT + "dirty", ROOT + "completed");
            doAnswer(call -> {
                scanning.countDown();
                return products.findAll(org.springframework.data.domain.PageRequest.of(0, 500,
                        org.springframework.data.domain.Sort.by("id"))).stream().map(Product::getId).toList();
            }).when(products).firstIds(any());
            Future<?> build = workers.submit(guard::rebuild);
            assertThat(scanning.await(300, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(store.snapshot()).isNull();
            commit.countDown(); UUID id = publication.get(10, TimeUnit.SECONDS); build.get(10, TimeUnit.SECONDS);
            assertThat(scanning.getCount()).isZero();
            assertThat(store.bloom(store.snapshot()).contains(id.toString())).isTrue();
            assertThat(service.getProductDetail(id)).containsEntry("id", id);
        } finally { commit.countDown(); workers.shutdownNow(); }
    }
    private void docker(String command) throws Exception {
        Process process = new ProcessBuilder("docker", command, "simplecard-product-guard-test").redirectErrorStream(true).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as(new String(process.getInputStream().readAllBytes())).isZero();
    }
    private void awaitRedis() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { if (store.runId() != null) return; }
            catch (RuntimeException e) { if (System.nanoTime() >= until) throw e; }
            Thread.sleep(50);
        }
    }
}
