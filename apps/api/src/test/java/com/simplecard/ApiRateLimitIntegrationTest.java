package com.simplecard;

import com.simplecard.config.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.service.ApiTrafficLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.mail.host=localhost","spring.datasource.url=jdbc:h2:mem:api_limits;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "api-rate-limit.enabled=true","api-rate-limit.address=redis://127.0.0.1:56382","api-rate-limit.password=",
        "api-rate-limit.prefix=api-limit-integration"})
@EnabledIfEnvironmentVariable(named="API_LIMIT_TESTS",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ApiRateLimitIntegrationTest {
    static final String ROOT="api-limit-integration:{api-limits}:";
    @Autowired ApiTrafficLimiter limiter;
    @Autowired ApiRateLimitProperties p;
    @Autowired ClientIpResolver ip;
    @Autowired MeterRegistry meters;
    @Autowired @Qualifier("apiLimiterRedis") RedissonClient redis;
    @BeforeEach void reset() {
        redis.getKeys().deleteByPattern("api-limit-integration:*");p.setMaxPeers(5000);p.setShadow(false);
        p.setPolicies(new ApiRateLimitProperties().getPolicies());
    }
    @Test void twoIndependentClientsShareExactlyOneQuotaAndRejectedPeersDoNotCreateKeys() throws Exception {
        p.getPolicies().put("catalog",new ApiRateLimitProperties.Policy(10000,100,100));
        Config config=new Config();config.setThreads(2).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:56382").setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        RedissonClient other=Redisson.create(config);ExecutorService workers=Executors.newFixedThreadPool(12);
        try {
            var factory=new DefaultListableBeanFactory();factory.registerSingleton("limiter",other);
            var second=new ApiTrafficLimiter(p,factory.getBeanProvider(RedissonClient.class),ip,meters);
            limiter.check("catalog","warm",true);second.check("catalog","warm",true);redis.getKeys().deleteByPattern("api-limit-integration:*");
            AtomicInteger accepted=new AtomicInteger();List<Future<?>> calls=new ArrayList<>();long start=System.nanoTime();
            for(int i=0;i<200;i++) {
                ApiTrafficLimiter target=i%2==0?limiter:second;
                calls.add(workers.submit(()->{try {target.check("catalog","one-peer",true);accepted.incrementAndGet();}
                    catch(BusinessException e){assertThat(e.getHttpStatus().value()).isEqualTo(429);}}));
            }
            for(var call:calls)call.get(8,TimeUnit.SECONDS);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(10000);
            assertThat(accepted).hasValue(100);
            long keys=redis.getKeys().count();
            for(int i=0;i<100;i++)assertThatThrownBy(()->second.check("catalog",UUID.randomUUID().toString(),true)).isInstanceOf(BusinessException.class);
            assertThat(redis.getKeys().count()).isEqualTo(keys);
        } finally {workers.shutdownNow();other.shutdown();}
    }
    @Test void longerWindowRetainsHistoryBeyondTwoSecondsAndRefreshesTwiceWindowTtl() throws Exception {
        p.getPolicies().put("catalog",new ApiRateLimitProperties.Policy(4000,10,1));
        limiter.check("catalog","peer",true);
        String peer=redis.getKeys().getKeysByPattern(ROOT+"p:catalog:4000:*").iterator().next();
        assertThat(redis.getBucket(peer).remainTimeToLive()).isBetween(7000L,8000L);
        Thread.sleep(2100);
        assertThatThrownBy(()->limiter.check("catalog","peer",true)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(429));
    }
    @Test void peerBudgetIsGlobalAcrossScopesAndFailsClosedForCostlyActions() {
        p.setMaxPeers(2);limiter.check("catalog","a",true);limiter.check("catalog","b",true);
        assertThatThrownBy(()->limiter.check("login","c",true)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(503));
        assertThat(redis.getScoredSortedSet(ROOT+"peers").size()).isEqualTo(2);
    }
    @Test void allLogMembersAreUniqueWithinOneMillisecondAndCounterIsObservable() {
        p.getPolicies().put("catalog",new ApiRateLimitProperties.Policy(10000,100,100));
        for(int i=0;i<30;i++)limiter.check("catalog","peer",true);
        assertThat(redis.getScoredSortedSet(ROOT+"g:catalog:10000").size()).isEqualTo(30);
        assertThat(meters.get("simplecard.api.admissions").tag("scope","catalog").tag("outcome","allowed").counter().count()).isGreaterThanOrEqualTo(30);
    }
    @Test void shadowObservesRejectionWithoutBlockingAndRealModeEnforces() {
        p.getPolicies().put("catalog",new ApiRateLimitProperties.Policy(10000,1,1));p.setShadow(true);
        long before=limiter.shadowRejected.sum();limiter.check("catalog","peer",true);limiter.check("catalog","peer",true);
        assertThat(limiter.shadowRejected.sum()).isEqualTo(before+1);p.setShadow(false);
        assertThatThrownBy(()->limiter.check("catalog","peer",true)).isInstanceOf(BusinessException.class);
    }
    @Test void shortGlobalAndLongOrderQuotasAreNotPartiallyCharged() {
        var upstream=p.getPolicies().get("txid-upstream");upstream.setPeer(5);
        UUID order=UUID.randomUUID();
        seed(ROOT+"g:txid-upstream:1000",5);
        assertThatThrownBy(()->limiter.external("txid-upstream","txid-order",order,3)).isInstanceOf(BusinessException.class);
        assertThat(redis.getScoredSortedSet(ROOT+"g:txid-order:86400000").isExists()).isFalse();
        redis.getKeys().delete(ROOT+"g:txid-upstream:1000");
        for(int i=0;i<3;i++)limiter.external("txid-upstream","txid-order",order,3);
        assertThatThrownBy(()->limiter.external("txid-upstream","txid-order",order,3)).isInstanceOf(BusinessException.class);
        assertThat(redis.getScoredSortedSet(ROOT+"g:txid-upstream:1000").size()).isEqualTo(3);
    }
    @Test void independentRedisOutageUsesBoundedFallbackAndBlocksExternalWork() throws Exception {
        docker("pause");
        try {
            assertThatThrownBy(()->limiter.external("txid-upstream","txid-order",UUID.randomUUID(),3))
                    .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(503));
            for(int i=0;i<10;i++)limiter.check("catalog","read-peer",false);
        } finally {docker("unpause");Thread.sleep(2200);}
        limiter.check("catalog","recovered",true);
    }
    @Test void concurrencyPermitIsBoundedAndReleasedIndependentlyOfWindowQuota() {
        List<ApiTrafficLimiter.Permit> held=new ArrayList<>();
        try {
            for(int i=0;i<8;i++)held.add(limiter.enterScope("query"));
            assertThatThrownBy(()->limiter.enterScope("query")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getHttpStatus().value()).isEqualTo(503));
        } finally {held.forEach(ApiTrafficLimiter.Permit::close);}
        limiter.enterScope("query").close();
    }
    private void seed(String key,int count) {
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,"local t=redis.call('TIME');local n=t[1]*1000+math.floor(t[2]/1000);for i=1,tonumber(ARGV[1]) do redis.call('ZADD',KEYS[1],n,tostring(i)) end;redis.call('PEXPIRE',KEYS[1],2000);return 1",
                RScript.ReturnType.INTEGER,List.of(key),count);
    }
    private void docker(String action) throws Exception {
        var process=new ProcessBuilder("docker",action,"simplecard-api-limit-test").redirectErrorStream(true).start();
        assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
    }
}
