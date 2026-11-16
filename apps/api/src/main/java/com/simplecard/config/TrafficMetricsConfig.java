package com.simplecard.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration
public class TrafficMetricsConfig {
    @Bean @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry trafficMeters() { return new SimpleMeterRegistry(); }
}
