package com.simplecard.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class ApiLimitRedisConfig {
    @Bean(destroyMethod="shutdown") @Lazy
    @ConditionalOnProperty(name="api-rate-limit.enabled",havingValue="true",matchIfMissing=true)
    public RedissonClient apiLimiterRedis(ApiRateLimitProperties p,ProductGuardProperties product,
            @org.springframework.beans.factory.annotation.Value("${coordination.address:redis://127.0.0.1:6379}") String coordination) {
        if(p.getAddress().equals(coordination) || p.getAddress().equals(product.getAddress()))
            throw new IllegalArgumentException("API limiter requires a separate Redis address");
        Config c=new Config(); c.setThreads(2).setNettyThreads(2);
        var s=c.useSingleServer().setAddress(p.getAddress()).setConnectTimeout(300).setTimeout(300).setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        if (!p.getPassword().isBlank()) s.setPassword(p.getPassword());
        return Redisson.create(c);
    }
}
