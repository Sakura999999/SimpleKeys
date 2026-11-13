package com.simplecard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.config.ProductGuardProperties;
import com.simplecard.repository.ProductDisplayFields;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Synchronous bounded rebuilds only: no stale responses, background refresh or unbounded queues. */
@Component
@Slf4j
public class ProductDisplayCache {
    private final ProductProtectionStore store;
    private final ProductGuardProperties p;
    private final ProductQueryProtection protection;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, CompletableFuture<Map<String,Object>>> inFlight = new ConcurrentHashMap<>();
    public final LongAdder hits = new LongAdder(), misses = new LongAdder(), rebuilds = new LongAdder(),
            coalesced = new LongAdder(), rejected = new LongAdder(), rejectedBySize = new LongAdder(), failures = new LongAdder();
    public ProductDisplayCache(ProductProtectionStore store, ProductGuardProperties p, ProductQueryProtection protection, ObjectMapper json) {
        this.store=store; this.p=p; this.protection=protection; this.json=json;
        if (p.getDisplayWaitMs()<1 || p.getDisplayWaitMs()>200 || p.getDisplayMaxEntryBytes()<1 || p.getDisplayMaxEntries()<1 ||
                p.getDisplayMaxEntries()>2000 || p.getDisplayMaxBytes()<1 || !Double.isFinite(p.getDisplayMemoryStopRatio()) ||
                p.getDisplayMemoryStopRatio()<=0 || p.getDisplayMemoryStopRatio()>0.8)
            throw new IllegalArgumentException("Invalid display cache quotas");
    }
    /** Capture BEFORE reading SQL, so an update between the SQL read and cache fill cannot publish old text. */
    public String context(UUID id) {
        if (!p.isEnabled() || !p.isDisplayCacheEnabled()) return null;
        try { return store.displayContext(id); } catch (RuntimeException e) { failures.increment(); return null; }
    }
    public Map<String,Object> get(UUID id, String context, Supplier<Map<String,Object>> loader) {
        protection.requireDatabaseScope();
        if (context==null) return loader.get();
        Map<String,Object> cached;
        try { cached=read(id,context); } catch (RuntimeException e) { failures.increment(); return loader.get(); }
        if (cached!=null) { hits.increment(); return cached; }
        misses.increment();
        String key=id+"@"+context;
        CompletableFuture<Map<String,Object>> own=new CompletableFuture<>();
        CompletableFuture<Map<String,Object>> current=inFlight.putIfAbsent(key,own);
        if (current!=null) {
            coalesced.increment();
            try { return new LinkedHashMap<>(current.get(p.getDisplayWaitMs(),TimeUnit.MILLISECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ProductQueryProtection.busy(); }
            catch (TimeoutException e) { throw ProductQueryProtection.busy(); }
            catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException failure) throw failure;
                throw new IllegalStateException(e.getCause());
            }
        }
        try {
            Map<String,Object> value=rebuild(id,context,loader);
            own.complete(value); return new LinkedHashMap<>(value);
        } catch (RuntimeException | Error e) { own.completeExceptionally(e); throw e; }
        finally { inFlight.remove(key,own); }
    }
    private Map<String,Object> rebuild(UUID id,String context,Supplier<Map<String,Object>> loader) {
        RLock lock;
        boolean held;
        try { lock=store.displayLock(id); held=lock.tryLock(p.getDisplayWaitMs(),TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ProductQueryProtection.busy(); }
        catch (RuntimeException e) { failures.increment(); return loader.get(); }
        try {
            Map<String,Object> cached;
            try { cached=read(id,context); } catch (RuntimeException e) { failures.increment(); return loader.get(); }
            if (cached!=null) { hits.increment(); return cached; }
            if (!held) throw ProductQueryProtection.busy();
            rebuilds.increment();
            Map<String,Object> value=loader.get();
            if (!ProductDisplayFields.KEYS.equals(value.keySet())) throw new IllegalArgumentException("Unexpected display fields");
            try {
                String encoded=json.writeValueAsString(value);
                if (encoded.getBytes(StandardCharsets.UTF_8).length>p.getDisplayMaxEntryBytes()) rejectedBySize.increment();
                else if (lock.isHeldByCurrentThread() && store.writeDisplay(id,context,encoded,ThreadLocalRandom.current().nextInt(300,421))!=1) rejected.increment();
            } catch (Exception e) { failures.increment(); }
            return value;
        } finally {
            if (held) try { if (lock.isHeldByCurrentThread()) lock.unlock(); } catch (RuntimeException e) { failures.increment(); }
        }
    }
    private Map<String,Object> read(UUID id,String context) {
        String value=store.readDisplay(id,context);
        if (value==null) return null;
        try {
            Map<String,Object> result=json.readValue(value,new TypeReference<>() {});
            if (!ProductDisplayFields.KEYS.equals(result.keySet()) || result.values().stream().anyMatch(v -> v!=null && !(v instanceof String)))
                throw new IllegalArgumentException("Unexpected display cache schema");
            return result;
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid display cache JSON",e); }
    }
    @org.springframework.scheduling.annotation.Scheduled(initialDelay=60000,fixedDelay=60000)
    public void metrics() {
        if (!p.isEnabled() || !p.isDisplayCacheEnabled()) return;
        log.info("Product display cache: hits={}, misses={}, rebuilds={}, coalesced={}, rejected={}, oversized={}, failures={}",
                hits.sum(),misses.sum(),rebuilds.sum(),coalesced.sum(),rejected.sum(),rejectedBySize.sum(),failures.sum());
        protection.runMaintenance(this::memoryMetrics);
    }
    private void memoryMetrics() {
        try {
            var usage=store.displayMemory();
            if (usage.get(1)>0 && usage.get(0)>=usage.get(1)*0.7)
                log.warn("Product Redis memory pressure: used={}, max={}, displayBytes={}, displayEntries={}",usage.get(0),usage.get(1),usage.get(2),usage.get(3));
        } catch (RuntimeException e) { failures.increment(); }
    }
}
