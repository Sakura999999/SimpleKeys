package com.simplecard.service;

import com.simplecard.config.ProductGuardProperties;
import com.simplecard.constant.ErrorCode;
import com.simplecard.entity.Product;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.function.Function;

@Component
@Slf4j
public class ProductQueryProtection {
    private final ProductProtectionStore store;
    private final ProductGuardProperties p;
    private final ProductRepository products;
    private final ProductPublicationBarrier publicationBarrier;
    private final Semaphore database;
    private final Semaphore requests = new Semaphore(32);
    private final ThreadLocal<Boolean> databaseScope = new ThreadLocal<>();
    private final Window fallbackHttp = new Window();
    private final Window fallbackDatabase = new Window();
    private final Window front = new Window();
    private final AtomicLong warningAfter = new AtomicLong();
    private final AtomicBoolean maintenanceQueued = new AtomicBoolean();
    private final ExecutorService maintenance = Executors.newSingleThreadExecutor(job -> {
        Thread worker = new Thread(job, "product-index-maintenance"); worker.setDaemon(true); return worker;
    });
    @Value("${product-guard.rebuild-ms:3600000}") private long rebuildMillis = 3600000;
    private volatile long rebuildAfter;
    public final LongAdder bloomRejected = new LongAdder(), negativeHits = new LongAdder(),
            databaseQueries = new LongAdder(), degraded = new LongAdder(), rateRejected = new LongAdder();

    public ProductQueryProtection(ProductProtectionStore store, ProductGuardProperties p, ProductRepository products,
                                  ProductPublicationBarrier publicationBarrier) {
        this.store = store; this.p = p; this.products = products;
        this.publicationBarrier = publicationBarrier;
        if (p.getPrefix().isBlank() || p.getCapacity() < 1 || !Double.isFinite(p.getFalseProbability()) || p.getFalseProbability() <= 0 || p.getFalseProbability() >= 1 ||
                p.getDatabaseConcurrency() < 1 || p.getGlobalPerSecond() < 1 || p.getIpPerSecond() < 1 ||
                p.getDatabasePerSecond() < 1 || p.getFallbackPerSecond() < 1 || p.getMaxNegativeEntries() < 1)
            throw new IllegalArgumentException("Invalid product-guard limits");
        database = new Semaphore(p.getDatabaseConcurrency());
    }
    public boolean enabled() { return p.isEnabled(); }
    /** Called by the early HTTP filter, including malformed UUID requests. */
    public void admit(String peer) {
        if (!enabled()) return;
        if (!front.take(p.getGlobalPerSecond())) throw limited();
        boolean accepted;
        try { accepted = store.admit("http", Integer.toHexString(peer.hashCode()), p.getGlobalPerSecond(), p.getIpPerSecond()); }
        catch (RuntimeException e) { unavailable(e); accepted = fallbackHttp.take(p.getFallbackPerSecond()); }
        if (!accepted) throw limited();
    }
    public boolean enterRequest() { return !enabled() || requests.tryAcquire(); }
    public void exitRequest() { if (enabled()) requests.release(); }
    public <T> T load(UUID id, Supplier<Optional<Product>> query, Function<Product,T> mapper) {
        if (!enabled()) return mapper.apply(publicProduct(query.get()));
        String snapshot = null;
        try {
            snapshot = store.snapshot();
            if (snapshot != null && !store.dirty(id)) {
                if (!store.dataExists(snapshot)) throw new IllegalStateException("Bloom data missing");
                boolean absent = store.negative(snapshot, id);
                boolean bloomAbsent = !absent && !store.bloom(snapshot).contains(id.toString());
                // Recheck both version and dirty status: a publication/rebuild can race contains().
                if ((absent || bloomAbsent) && store.canReject(snapshot, id)) {
                    if (absent) negativeHits.increment(); else bloomRejected.increment();
                    throw missing();
                }
            }
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { unavailable(e); snapshot = null; }
        boolean admitted;
        try { admitted = store.admit("database", "all", p.getDatabasePerSecond(), p.getDatabasePerSecond()); }
        catch (RuntimeException e) { unavailable(e); admitted = fallbackDatabase.take(p.getFallbackPerSecond()); }
        if (!admitted || !database.tryAcquire()) throw busy();
        try {
            databaseScope.set(true);
            databaseQueries.increment();
            Optional<Product> result = query.get();
            if (result.isEmpty() || result.get().getIsDeleted() != 0 || !result.get().isEnabled()) {
                if (snapshot != null) {
                    try { store.cacheNegative(snapshot, id, ThreadLocalRandom.current().nextInt(30, 61)); }
                    catch (RuntimeException e) { unavailable(e); }
                }
                throw missing();
            }
            return mapper.apply(result.get());
        } finally { databaseScope.remove(); database.release(); }
    }
    public void requireDatabaseScope() {
        if (enabled() && !Boolean.TRUE.equals(databaseScope.get()))
            throw new IllegalStateException("Display cache rebuild requires the existing database admission scope");
    }
    private Product publicProduct(Optional<Product> result) {
        return result.filter(v -> v.getIsDeleted() == 0 && v.isEnabled()).orElseThrow(ProductQueryProtection::missing);
    }
    /** Must execute before the transaction commits; failure aborts publication rather than hiding new products. */
    public void beforeMutation() { if (enabled()) publicationBarrier.enter(); }
    public void mutation(UUID id) {
        if (!enabled()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("Product mutation requires transaction");
        String token = UUID.randomUUID().toString();
        try { store.beginMutation(id, token); }
        catch (RuntimeException e) { unavailable(e); throw busy(); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try { store.completeMutation(id, token); }
                catch (RuntimeException e) { unavailable(e); /* Pending ID remains safe across process failure. */ }
                finally { rebuildAfter = 0; }
            }
        });
    }
    @Scheduled(initialDelayString = "${product-guard.initial-delay-ms:1000}", fixedDelay = 10000)
    public void maintain() {
        runMaintenance(this::maintainNow);
    }
    public void runMaintenance(Runnable job) {
        // Do not monopolize Spring's shared scheduler used for payment replay and order expiry.
        if (!enabled() || !maintenanceQueued.compareAndSet(false, true)) return;
        try {
            maintenance.execute(() -> {
                try { job.run(); } finally { maintenanceQueued.set(false); }
            });
        } catch (RejectedExecutionException e) { maintenanceQueued.set(false); }
    }
    private void maintainNow() {
        try {
            store.pruneDisplay();
            String active = store.snapshot();
            if (System.currentTimeMillis() < rebuildAfter && active != null && store.dataExists(active)) return;
        } catch (RuntimeException e) { unavailable(e); }
        rebuild();
    }
    @jakarta.annotation.PreDestroy
    public void stopMaintenance() { maintenance.shutdownNow(); }
    public void rebuild() {
        if (!enabled()) return;
        RLock lock = null;
        boolean held = false;
        String next = null;
        boolean published = false;
        try {
            lock = store.rebuildLock();
            held = lock.tryLock();
            if (!held) return;
            String token = UUID.randomUUID().toString();
            store.claimBuild(token);
            next = store.runId() + "|" + UUID.randomUUID();
            publicationBarrier.awaitPreviousMutations(store::recoverPending);
            var bloom = store.bloom(next);
            long count = products.count();
            Map<String,String> completed = store.completedMutations();
            long capacity = Math.max(p.getCapacity(), Math.multiplyExact(count + completed.size(), 3));
            bloom.tryInit(capacity, p.getFalseProbability());
            bloom.add("__filter_ready__"); // materialize bit storage even for an empty catalog
            // Temp filters expire after crashes. Active filters have no TTL.
            bloom.expire(Duration.ofHours(2));
            for (String id : completed.values()) bloom.add(id);
            UUID cursor = null;
            while (true) {
                List<UUID> ids = cursor == null ? products.firstIds(PageRequest.of(0, 500)) : products.idsAfter(cursor, PageRequest.of(0, 500));
                if (ids.isEmpty()) break;
                for (UUID id : ids) bloom.add(id.toString());
                cursor = ids.getLast();
                if (ids.size() < 500) break;
            }
            if (!lock.isHeldByCurrentThread()) return;
            published = store.publish(next, token, completed);
            if (published) {
                rebuildAfter = System.currentTimeMillis() + rebuildMillis;
                log.info("Product Bloom rebuilt: databaseIds={}, capacity={}, completedMutations={}", count, capacity, completed.size());
            }
        } catch (RuntimeException e) { unavailable(e); }
        finally {
            if (!published && next != null) {
                try { store.retireIfUnpublished(next); } catch (RuntimeException ignored) { }
            }
            if (held && lock != null) {
                try { if (lock.isHeldByCurrentThread()) lock.unlock(); } catch (RuntimeException ignored) { }
            }
        }
    }
    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void metrics() {
        if (enabled()) log.info("Product guard: bloomRejected={}, negativeHits={}, databaseQueries={}, degraded={}, rateRejected={}",
                bloomRejected.sum(), negativeHits.sum(), databaseQueries.sum(), degraded.sum(), rateRejected.sum());
    }
    private void unavailable(RuntimeException e) {
        degraded.increment();
        long now = System.currentTimeMillis(), after = warningAfter.get();
        if (now >= after && warningAfter.compareAndSet(after, now + 30000))
            log.warn("Product cache unavailable; bounded database fallback ({})", e.getClass().getSimpleName());
    }
    public BusinessException limited() {
        rateRejected.increment();
        return new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
    }
    public static BusinessException busy() {
        return new BusinessException(ErrorCode.MAINTENANCE, "商品服务繁忙，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
    }
    private static BusinessException missing() {
        return new BusinessException(ErrorCode.PRODUCT_NOT_FOUND, "商品不存在或已下架");
    }
    private static class Window {
        final ArrayDeque<Long> events = new ArrayDeque<>();
        synchronized boolean take(int limit) {
            long now = System.nanoTime();
            while (!events.isEmpty() && events.peekFirst() <= now - 1_000_000_000L) events.removeFirst();
            if (events.size() >= limit) return false;
            events.addLast(now); return true;
        }
    }
}
