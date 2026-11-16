package com.simplecard.service;

import com.simplecard.config.*;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;

@Component @Slf4j
public class ApiTrafficLimiter {
    private final ApiRateLimitProperties p;
    private final ObjectProvider<RedissonClient> clients;
    private final ClientIpResolver ip;
    private final LocalRequestWindows fallback;
    private final Map<String,java.util.concurrent.Semaphore> permits=new java.util.concurrent.ConcurrentHashMap<>();
    private final io.micrometer.core.instrument.MeterRegistry meters;
    private volatile long retryAfter;
    private final AtomicLong warnAfter=new AtomicLong();
    public final LongAdder allowed=new LongAdder(),rejected=new LongAdder(),degraded=new LongAdder(),shadowRejected=new LongAdder();
    public ApiTrafficLimiter(ApiRateLimitProperties p,@Qualifier("apiLimiterRedis") ObjectProvider<RedissonClient> clients,ClientIpResolver ip,
                             io.micrometer.core.instrument.MeterRegistry meters) {
        this.p=p;this.clients=clients;this.ip=ip;
        this.meters=meters;
        if (p.getMaxPeers()<1 || p.getMaxPeers()>10000 || !Double.isFinite(p.getMemoryStopRatio()) || p.getMemoryStopRatio()<=0 || p.getMemoryStopRatio()>0.8)
            throw new IllegalArgumentException("Invalid API limiter capacity");
        if(p.getPrefix().isBlank() || p.getPolicies().size()>32 || p.getPolicies().values().stream().mapToLong(ApiRateLimitProperties.Policy::getGlobal).sum()>50000)
            throw new IllegalArgumentException("API limiter aggregate policy budget exceeded");
        p.getPolicies().forEach((name,policy)->{validate(name,policy);permits.put(name,new java.util.concurrent.Semaphore(policy.getConcurrency()));});
        fallback=new LocalRequestWindows(p.getMaxPeers()+100);
    }
    private void validate(String name,ApiRateLimitProperties.Policy s) {
        if (!name.matches("[a-z-]{1,40}") || s.getWindowMs()<1000 || s.getWindowMs()>86400000 ||
                s.getGlobal()<1 || s.getGlobal()>20000 || s.getPeer()<1 || s.getPeer()>s.getGlobal())
            throw new IllegalArgumentException("Invalid API limiter policy: "+name);
        if(s.getConcurrency()<1 || s.getConcurrency()>64)throw new IllegalArgumentException("Invalid scope concurrency");
    }
    public Permit enterScope(String scope) {
        if(!p.isEnabled())return new Permit(null);
        var slot=permits.get(scope);
        if(slot==null || !slot.tryAcquire()) {
            rejected.increment();record(scope,"concurrency_rejected");
            throw new BusinessException(ErrorCode.MAINTENANCE,"服务繁忙，请稍后重试",HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new Permit(slot);
    }
    public static final class Permit implements AutoCloseable {
        private java.util.concurrent.Semaphore slot;
        Permit(java.util.concurrent.Semaphore slot){this.slot=slot;}
        public void close(){if(slot!=null){slot.release();slot=null;}}
    }
    public void ip(String scope) { check(scope,ip.resolveCurrent(),false); }
    public void order(String scope,UUID order,boolean strict) { check(scope,order.toString(),strict); }
    public void external(String upstream,String orderScope,UUID order,int perOrderLimit) {
        if(!p.isEnabled())return;
        var up=p.getPolicies().get(upstream);var per=p.getPolicies().get(orderScope);
        validate(upstream,up);validate(orderScope,per);
        if(perOrderLimit<1 || perOrderLimit>20)throw new IllegalArgumentException("Invalid order quota");
        String root=p.getPrefix()+":{api-limits}:";
        String g1=root+"g:"+upstream+":"+up.getWindowMs(),g2=root+"g:"+orderScope+":"+per.getWindowMs();
        String k1=root+"p:"+upstream+":"+up.getWindowMs()+":"+hash(ip.resolveCurrent());
        String k2=root+"p:"+orderScope+":"+per.getWindowMs()+":"+hash(order.toString());
        boolean ok;
        try {
            if(retryAfter!=0 && System.nanoTime()-retryAfter<0)throw new IllegalStateException("Limiter circuit open");
            Long result=clients.getObject().getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,SlidingWindowScript.COMBINED,
                    RScript.ReturnType.INTEGER,List.of(g1,k1,g2,k2,root+"peers"),UUID.randomUUID().toString(),p.getMaxPeers(),p.getMemoryStopRatio(),
                    up.getWindowMs(),up.getGlobal(),up.getPeer(),per.getWindowMs(),per.getGlobal(),perOrderLimit);
            if(result<0){record(upstream,result==-1?"peer_budget":"memory_budget");throw new CapacityUnavailable();}ok=result==1;
        } catch(RuntimeException e) {
            if(!(e instanceof CapacityUnavailable) && !ProductProtectionStore.capacityError(e) && !(e instanceof IllegalStateException))
                retryAfter=System.nanoTime()+2_000_000_000L;
            degraded.increment();record(upstream,"degraded");
            if(p.isShadow()) { shadowRejected.increment();record(upstream,"shadow_rejected");return; }
            throw new BusinessException(ErrorCode.MAINTENANCE,"操作服务繁忙，请稍后重试",HttpStatus.SERVICE_UNAVAILABLE);
        }
        finish(upstream,ok);
    }
    public void check(String scope,String identity,boolean strict) {
        if (!p.isEnabled()) return;
        var policy=p.getPolicies().get(scope);
        if (policy==null) throw new IllegalArgumentException("Unknown rate scope: "+scope);
        validate(scope,policy);
        boolean ok;
        try {
            if (retryAfter!=0 && System.nanoTime()-retryAfter<0) throw new IllegalStateException("Limiter circuit open");
            String root=p.getPrefix()+":{api-limits}:";
            String global=root+"g:"+scope+":"+policy.getWindowMs(),peer=root+"p:"+scope+":"+policy.getWindowMs()+":"+hash(identity);
            Long decision=clients.getObject().getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,SlidingWindowScript.LUA,
                    RScript.ReturnType.INTEGER,List.of(global,peer,root+"peers"),policy.getGlobal(),policy.getPeer(),
                    UUID.randomUUID().toString(),policy.getWindowMs(),p.getMaxPeers(),p.getMemoryStopRatio());
            if (decision<0) {record(scope,decision==-1?"peer_budget":"memory_budget");throw new CapacityUnavailable();}
            ok=decision==1;
        } catch (RuntimeException e) {
            if (!(e instanceof CapacityUnavailable) && !ProductProtectionStore.capacityError(e) && !(e instanceof IllegalStateException))
                retryAfter=System.nanoTime()+2_000_000_000L;
            degraded.increment();
            record(scope,"degraded");
            long now=System.nanoTime(),warn=warnAfter.get();
            if ((warn==0 || now-warn>=0) && warnAfter.compareAndSet(warn,now+30_000_000_000L)) log.warn("API limiter degraded ({})",e.getClass().getSimpleName());
            if (strict && !p.isShadow()) throw new BusinessException(ErrorCode.MAINTENANCE,"操作服务繁忙，请稍后重试",HttpStatus.SERVICE_UNAVAILABLE);
            ok=fallback.admit(scope,hash(identity),policy.getWindowMs(),Math.max(1,policy.getGlobal()/4),policy.getPeer());
        }
        finish(scope,ok);
    }
    private void finish(String scope,boolean ok) {
        if (!ok) {
            if (p.isShadow()) { shadowRejected.increment();record(scope,"shadow_rejected");return; }
            rejected.increment();record(scope,"rejected");throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"操作过于频繁，请稍后再试",HttpStatus.TOO_MANY_REQUESTS);
        }
        allowed.increment();record(scope,"allowed");
    }
    private void record(String scope,String outcome) { meters.counter("simplecard.api.admissions","scope",scope,"outcome",outcome).increment(); }
    public Map<String,Map<String,Double>> snapshot() {
        Map<String,Map<String,Double>> result=new LinkedHashMap<>();
        for(String scope:p.getPolicies().keySet()) {
            Map<String,Double> counts=new LinkedHashMap<>();
            for(String outcome:List.of("allowed","rejected","degraded","shadow_rejected","concurrency_rejected","peer_budget","memory_budget")) {
                var counter=meters.find("simplecard.api.admissions").tag("scope",scope).tag("outcome",outcome).counter();
                counts.put(outcome,counter==null?0.0:counter.count());
            }
            result.put(scope,counts);
        }
        return result;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static class CapacityUnavailable extends RuntimeException {}
    @org.springframework.scheduling.annotation.Scheduled(initialDelay=60000,fixedDelay=60000)
    public void metrics() { if(p.isEnabled()) log.info("API limits: allowed={}, rejected={}, degraded={}, shadowRejected={}",allowed.sum(),rejected.sum(),degraded.sum(),shadowRejected.sum()); }
}
