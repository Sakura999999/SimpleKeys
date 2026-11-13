package com.simplecard.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
public class ProductGuardRedisConfig {
    @Bean(destroyMethod = "shutdown")
    @Lazy
    @ConditionalOnProperty(name = "product-guard.enabled", havingValue = "true", matchIfMissing = true)
    public RedissonClient productGuardRedis(ProductGuardProperties p) {
        Config config = new Config();
        config.setThreads(2).setNettyThreads(2);
        var server = config.useSingleServer().setAddress(p.getAddress())
                .setConnectTimeout(300).setTimeout(300).setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        if (!p.getPassword().isBlank()) server.setPassword(p.getPassword());
        return Redisson.create(config);
    }
}
