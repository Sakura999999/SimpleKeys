package com.simplecard.config;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration
@ConditionalOnProperty(name="coordination.enabled", havingValue="true", matchIfMissing=true)
public class RedisCoordinationConfig {
    @Bean(destroyMethod="shutdown") @Lazy @Primary
    public RedissonClient coordinationRedis(
            @Value("${coordination.address:redis://127.0.0.1:6379}") String address,
            @Value("${coordination.password:}") String password,
            @Value("${coordination.watchdog-ms:30000}") long watchdog) {
        Config config = new Config(); config.setThreads(4).setNettyThreads(4); config.setLockWatchdogTimeout(watchdog);
        var server = config.useSingleServer().setAddress(address).setConnectTimeout(1500).setTimeout(1500)
                .setRetryAttempts(0).setConnectionMinimumIdleSize(1).setConnectionPoolSize(16)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(4);
        if (!password.isBlank()) server.setPassword(password);
        return Redisson.create(config);
    }
}
