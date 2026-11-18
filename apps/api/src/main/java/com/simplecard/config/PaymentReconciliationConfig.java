package com.simplecard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class PaymentReconciliationConfig {
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler paymentReconciliationScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("payment-reconciliation-");
        return scheduler;
    }
}
