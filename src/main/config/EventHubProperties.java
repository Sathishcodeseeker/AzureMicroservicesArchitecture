package com.enterprise.eventhub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Azure Event Hub Configuration Properties
 * 
 * Binds properties from application.yml to type-safe configuration
 */
@Configuration
@ConfigurationProperties(prefix = "azure.eventhub")
@Data
public class EventHubProperties {
    
    private String namespace;
    private String connectionString;
    private String eventHubName;
    private String consumerGroup;
    
    private CheckpointStore checkpointStore = new CheckpointStore();
    private Backpressure backpressure = new Backpressure();
    private Retry retry = new Retry();
    
    @Data
    public static class CheckpointStore {
        private String connectionString;
        private String containerName;
    }
    
    @Data
    public static class Backpressure {
        private Integer maxBatchSize = 100;
        private Integer maxWaitTimeSeconds = 10;
        private Integer prefetchCount = 300;
    }
    
    @Data
    public static class Retry {
        private Integer maxRetries = 3;
        private Long backoffDelayMs = 1000L;
        private Long maxBackoffDelayMs = 30000L;
    }
}
