package com.enterprise.eventhub.service;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.enterprise.eventhub.config.EventHubProperties;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Azure Event Hub Publisher Service
 * 
 * 5W1H Analysis:
 * WHO: CDC Processor sending events to Azure Event Hub
 * WHAT: Publishes events with resilience patterns (circuit breaker, retry, backpressure)
 * WHEN: Triggered by CDC polling service
 * WHERE: Publishes to Azure Event Hub namespace
 * WHY: Provides reliable event delivery with fault tolerance
 * HOW: Uses Azure SDK with Resilience4j annotations
 * 
 * NFR Implementation:
 * 1. Circuit Breaker - Prevents cascading failures
 * 2. Retry - Handles transient failures
 * 3. Rate Limiter - Controls request rate
 * 4. Bulkhead - Isolates resources
 * 5. Backpressure - Handles slow consumers
 */
@Service
@Slf4j
public class EventHubPublisherService {
    
    private final EventHubProperties properties;
    private EventHubProducerClient producerClient;
    
    public EventHubPublisherService(EventHubProperties properties) {
        this.properties = properties;
    }
    
    /**
     * Initialize Event Hub Producer Client
     * 
     * Configuration includes:
     * - Retry policy for transient failures
     * - Batching options for throughput
     * - Timeout settings
     */
    @PostConstruct
    public void initialize() {
        log.info("Initializing Event Hub Producer Client");
        
        EventHubClientBuilder builder = new EventHubClientBuilder()
            .connectionString(
                properties.getConnectionString(),
                properties.getEventHubName()
            )
            .retry(new com.azure.core.amqp.AmqpRetryOptions()
                .setMaxRetries(properties.getRetry().getMaxRetries())
                .setDelay(java.time.Duration.ofMillis(properties.getRetry().getBackoffDelayMs()))
                .setMaxDelay(java.time.Duration.ofMillis(properties.getRetry().getMaxBackoffDelayMs()))
                .setTryTimeout(java.time.Duration.ofSeconds(30))
            );
        
        producerClient = builder.buildProducerClient();
        
        log.info("Event Hub Producer Client initialized successfully");
    }
    
    /**
     * Publish single event to Event Hub
     * 
     * Resilience Patterns Applied:
     * - @CircuitBreaker: Opens circuit if failure rate exceeds threshold
     * - @Retry: Retries on transient failures with exponential backoff
     * - @RateLimiter: Limits requests per second
     * - @Bulkhead: Limits concurrent calls
     * 
     * @param eventData Event data as JSON string
     * @param partitionKey Key for partition routing
     * @return CompletableFuture for async processing
     */
    @CircuitBreaker(name = "eventHubPublisher", fallbackMethod = "publishFallback")
    @Retry(name = "eventHubPublisher")
    @RateLimiter(name = "eventHubPublisher")
    @Bulkhead(name = "eventHubPublisher")
    public CompletableFuture<Void> publishEvent(String eventData, String partitionKey) {
        log.debug("Publishing single event to Event Hub: partitionKey={}", partitionKey);
        
        return CompletableFuture.runAsync(() -> {
            try {
                EventData event = new EventData(eventData);
                
                // Set partition key for ordered processing
                CreateBatchOptions options = new CreateBatchOptions()
                    .setPartitionKey(partitionKey);
                
                EventDataBatch batch = producerClient.createBatch(options);
                
                if (!batch.tryAdd(event)) {
                    throw new EventHubPublishException(
                        "Event is too large to fit in a batch"
                    );
                }
                
                producerClient.send(batch);
                
                log.debug("Event published successfully: partitionKey={}", partitionKey);
                
            } catch (Exception e) {
                log.error("Failed to publish event: partitionKey={}", partitionKey, e);
                throw new EventHubPublishException("Failed to publish event", e);
            }
        });
    }
    
    /**
     * Publish batch of events to Event Hub
     * 
     * Implements backpressure handling:
     * - Splits large batches into smaller chunks
     * - Respects Event Hub batch size limits
     * - Provides progress feedback
     * 
     * @param events List of event payloads
     * @param partitionKey Partition key for all events
     * @return CompletableFuture with published count
     */
    @CircuitBreaker(name = "eventHubPublisher", fallbackMethod = "publishBatchFallback")
    @Retry(name = "eventHubPublisher")
    @RateLimiter(name = "eventHubPublisher")
    @Bulkhead(name = "eventHubPublisher")
    public CompletableFuture<Integer> publishBatch(List<String> events, String partitionKey) {
        log.info("Publishing batch to Event Hub: count={}, partitionKey={}", 
            events.size(), partitionKey);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                int published = 0;
                int totalEvents = events.size();
                
                CreateBatchOptions options = new CreateBatchOptions()
                    .setPartitionKey(partitionKey)
                    .setMaximumSizeInBytes(properties.getBackpressure().getMaxBatchSize() * 1024);
                
                EventDataBatch currentBatch = producerClient.createBatch(options);
                
                for (int i = 0; i < totalEvents; i++) {
                    String eventData = events.get(i);
                    EventData event = new EventData(eventData);
                    
                    // Try to add to current batch
                    if (!currentBatch.tryAdd(event)) {
                        // Send current batch and create new one
                        producerClient.send(currentBatch);
                        published += currentBatch.getCount();
                        
                        log.debug("Batch sent: count={}, total_published={}/{}", 
                            currentBatch.getCount(), published, totalEvents);
                        
                        // Create new batch
                        currentBatch = producerClient.createBatch(options);
                        
                        // Add event to new batch
                        if (!currentBatch.tryAdd(event)) {
                            throw new EventHubPublishException(
                                "Single event too large for batch"
                            );
                        }
                    }
                    
                    // Backpressure: Add delay if needed
                    if ((i + 1) % properties.getBackpressure().getMaxBatchSize() == 0) {
                        Thread.sleep(properties.getBackpressure().getMaxWaitTimeSeconds() * 100L);
                    }
                }
                
                // Send remaining events
                if (currentBatch.getCount() > 0) {
                    producerClient.send(currentBatch);
                    published += currentBatch.getCount();
                }
                
                log.info("Batch published successfully: total={}, partitionKey={}", 
                    published, partitionKey);
                
                return published;
                
            } catch (Exception e) {
                log.error("Failed to publish batch: partitionKey={}", partitionKey, e);
                throw new EventHubPublishException("Failed to publish batch", e);
            }
        });
    }
    
    /**
     * Fallback method for single event publish
     * 
     * Called when circuit breaker opens or retries exhausted
     */
    @SuppressWarnings("unused")
    private CompletableFuture<Void> publishFallback(
        String eventData, 
        String partitionKey, 
        Exception e
    ) {
        log.error("Fallback triggered for event publish: partitionKey={}", partitionKey, e);
        
        return CompletableFuture.failedFuture(
            new EventHubPublishException("Circuit breaker open or retries exhausted", e)
        );
    }
    
    /**
     * Fallback method for batch publish
     */
    @SuppressWarnings("unused")
    private CompletableFuture<Integer> publishBatchFallback(
        List<String> events, 
        String partitionKey, 
        Exception e
    ) {
        log.error("Fallback triggered for batch publish: count={}, partitionKey={}", 
            events.size(), partitionKey, e);
        
        return CompletableFuture.failedFuture(
            new EventHubPublishException("Circuit breaker open or retries exhausted", e)
        );
    }
    
    /**
     * Get Event Hub partition information
     * 
     * Useful for monitoring and load balancing
     */
    public List<String> getPartitionIds() {
        EventHubProperties props = producerClient.getEventHubProperties();
        return props.getPartitionIds().stream().collect(Collectors.toList());
    }
    
    /**
     * Get partition information for specific partition
     */
    public PartitionProperties getPartitionProperties(String partitionId) {
        return producerClient.getPartitionProperties(partitionId);
    }
    
    /**
     * Close producer client on shutdown
     */
    @PreDestroy
    public void cleanup() {
        log.info("Closing Event Hub Producer Client");
        if (producerClient != null) {
            producerClient.close();
        }
    }
    
    /**
     * Custom exception for Event Hub publishing failures
     */
    public static class EventHubPublishException extends RuntimeException {
        public EventHubPublishException(String message) {
            super(message);
        }
        
        public EventHubPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
