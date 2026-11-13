package com.simplecard.service;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Redis locks surround transaction interceptors, including their COMMIT/ROLLBACK. */
@Slf4j @Component
public class RedisOrderCoordinator {
    @FunctionalInterface public interface Operation<T> { T run() throws Throwable; }
    private record Held(String key, RLock lock) {}
    private final ObjectProvider<RedissonClient> clients;
    private final boolean enabled;
    private final String prefix;
    private final long waitMillis;
    private final ThreadLocal<List<Held>> held = new ThreadLocal<>();
    public RedisOrderCoordinator(ObjectProvider<RedissonClient> clients,
            @Value("${coordination.enabled:true}") boolean enabled,
            @Value("${coordination.prefix:simplecard}") String prefix,
            @Value("${coordination.wait-ms:200}") long waitMillis) {
        this.clients=clients; this.enabled=enabled; this.prefix=prefix; this.waitMillis=waitMillis;
    }
    public boolean enabled() { return enabled; }
    public boolean active() { return held.get()!=null; }
    public String orderKey(UUID id) { return prefix+":lock:1:order:"+id; }
    public String stockKey(UUID product, UUID spec) { return prefix+":lock:2:stock:"+product+":"+(spec==null?"default":spec); }
    public String requestKey(String key) {
        try { return prefix+":lock:0:request:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public void requireHeld(Collection<String> keys) {
        if (!enabled) return;
        List<Held> locks=held.get();
        for (String key:keys) {
            Held found=locks==null?null:locks.stream().filter(h->h.key().equals(key)).findFirst().orElse(null);
            if (found==null) throw new BusinessException(ErrorCode.BAD_REQUEST,"订单或购物车内容已变化，请重试");
            try { if (!found.lock().isHeldByCurrentThread()) throw busy(); }
            catch (org.redisson.client.RedisException e) { throw busy(); }
        }
    }
    public <T> T execute(Collection<String> keys, Operation<T> operation) throws Throwable {
        if (!enabled) return operation.run();
        if (active()) { requireHeld(keys); return operation.run(); }
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Redis coordination must start before the database transaction");
        List<Held> acquired=new ArrayList<>();
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(waitMillis);
        try {
            for (String key:new TreeSet<>(keys)) {
                RLock lock;
                try {
                    lock=clients.getObject().getLock(key);
                    long remaining=Math.max(0,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                    // No explicit leaseTime: use watchdog renewal.
                    if (!lock.tryLock(remaining,TimeUnit.MILLISECONDS)) throw busy();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw busy(); }
                catch (org.redisson.client.RedisException | org.springframework.beans.BeansException e) { log.warn("Redis coordination unavailable ({})",e.getClass().getSimpleName()); throw busy(); }
                acquired.add(new Held(key,lock));
            }
            held.set(acquired);
            requireHeld(keys);
            return operation.run();
        } finally {
            held.remove();
            for (int i=acquired.size()-1;i>=0;i--) {
                RLock lock=acquired.get(i).lock();
                try { if(lock.isHeldByCurrentThread()) lock.unlock(); else log.warn("Redis lock ownership expired before completion"); }
                catch (RuntimeException e) { log.warn("Redis unlock deferred to lease expiry ({})",e.getClass().getSimpleName()); }
            }
        }
    }
    private BusinessException busy() { return new BusinessException(ErrorCode.SERVER_ERROR,"系统繁忙，请稍后重试",HttpStatus.SERVICE_UNAVAILABLE,null); }
}
