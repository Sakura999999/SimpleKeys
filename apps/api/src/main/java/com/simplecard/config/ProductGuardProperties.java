package com.simplecard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("product-guard")
public class ProductGuardProperties {
    private boolean enabled = true;
    private String address = "redis://127.0.0.1:6380";
    private String password = "";
    private String prefix = "simplecard:product-guard";
    private long capacity = 10000;
    private double falseProbability = 0.001;
    private int globalPerSecond = 300;
    private int ipPerSecond = 30;
    private int databasePerSecond = 100;
    private int fallbackPerSecond = 20;
    private int databaseConcurrency = 8;
    private int maxNegativeEntries = 5000;
    private boolean displayCacheEnabled = true;
    private int displayMaxEntryBytes = 16384;
    private int displayMaxEntries = 2000;
    private long displayMaxBytes = 33554432;
    private double displayMemoryStopRatio = 0.8;
    private int displayWaitMs = 100;
}
