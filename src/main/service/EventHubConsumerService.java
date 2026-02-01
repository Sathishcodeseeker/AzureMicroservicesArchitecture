package com.enterprise.eventhub.service;

import com.azure.messaging.eventhubs.*;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.messaging.eventhubs.models.*;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.enterprise.eventhub.config.EventHubProperties;
import com.enterprise.eventhub.service.handler.EventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Azure Event Hub Consumer Service
 * 
 * 5W1H Analysis:
 * WHO: Event consumers processing events from Event Hub
 * WHAT: Subscribes to Event Hub and processes events with checkpointing
 * WHEN: Starts on application startup, runs continuously
 * WHERE: Consumes from Azure Event Hub partitions
 * WHY: Enables event-driven architecture and inter-service communication
 * HOW: Uses EventProcessorClient with checkpoint store for at-least-once delivery
 * 
 * Key Features:
 * 1. Partition-based processing for parallelism
 * 2. Checkpoint management for progress tracking
 * 3. Backpressure handling with prefetch control
 * 4. Error handling and retry logic
 * 5. Load balancing across multiple consumers
 */
@Service
@Slf4j
public class EventHubConsumerService {
    
    private final EventHubProperties properties;
    private final EventHandler eventHandler;
    private EventProcessorClient processorClient;
    
    public EventHubConsumerService(
        EventHubProperties properties,
        EventHandler eventHandler
    ) {
        this.properties = properties;
        this.eventHandler = eventHandler;
    }
    
    /**
     * Initialize Event Hub Consumer
     * 
     * Sets up:
     * - Blob checkpoint store for progress tracking
     * - Event processor with partition handlers
     * - Error handlers for resilience
     * - Backpressure configuration
     */
    @PostConstruct
    public void startConsuming() {
        log.info("Starting Event Hub Consumer");
        
        try {
            // Create checkpoint store
            BlobContainerAsyncClient blobContainerAsyncClient = 
                new BlobContainerClientBuilder()
                    .connectionString(properties.getCheckpointStore().getConnectionString())
                    .containerName(properties.getCheckpointStore().getContainerName())
                    .buildAsyncClient();
            
            BlobCheckpointStore checkpointStore = 
                new BlobCheckpointStore(blobContainerAsyncClient);
            
            // Create event processor
            processorClient = new EventProcessorClientBuilder()
                .connectionString(
                    properties.getConnectionString(),
                    properties.getEventHubName()
                )
                .consumerGroup(properties.getConsumerGroup())
                .checkpointStore(checkpointStore)
                .processEvent(processEventConsumer())
                .processError(processErrorConsumer())
                .processPartitionInitialization(initializationConsumer())
                .processPartitionClose(closeConsumer())
                .prefetchCount(properties.getBackpressure().getPrefetchCount())
                .buildEventProcessorClient();
            
            // Start processing
            processorClient.start();
            
            log.info("Event Hub Consumer started successfully");
            
        } catch (Exception e) {
            log.error("Failed to start Event Hub Consumer", e);
            throw new RuntimeException("Failed to start consumer", e);
        }
    }
    
    /**
     * Process Event Consumer
     * 
     * Called for each event received from Event Hub
     * 
     * Processing Flow:
     * 1. Receive event from partition
     * 2. Extract and deserialize event data
     * 3. Delegate to appropriate event handler
     * 4. Checkpoint progress on success
     * 5. Handle errors and implement retry logic
     * 
     * Backpressure Handling:
     * - Prefetch count limits in-flight events
     * - Checkpoint frequency controls memory usage
     * - Slow processing automatically applies backpressure
     */
    private Consumer<EventContext> processEventConsumer() {
        return eventContext -> {
            PartitionContext partition = eventContext.getPartitionContext();
            EventData eventData = eventContext.getEventData();
            
            try {
                log.debug("Processing event: partition={}, offset={}, sequenceNumber={}", 
                    partition.getPartitionId(),
                    eventData.getOffset(),
                    eventData.getSequenceNumber());
                
                // Extract event properties
                String eventType = eventData.getProperties().get("eventType") != null 
                    ? eventData.getProperties().get("eventType").toString() 
                    : "Unknown";
                
                String payload = eventData.getBodyAsString();
                
                // Delegate to event handler based on type
                boolean processed = eventHandler.handleEvent(eventType, payload, eventData);
                
                if (processed) {
                    // Checkpoint on successful processing
                    eventContext.updateCheckpoint();
                    
                    log.debug("Event processed and checkpointed: type={}, partition={}", 
                        eventType, partition.getPartitionId());
                } else {
                    log.warn("Event processing returned false: type={}, partition={}", 
                        eventType, partition.getPartitionId());
                    
                    // Don't checkpoint - will be reprocessed
                    // Implement dead letter logic if needed
                }
                
            } catch (Exception e) {
                log.error("Error processing event: partition={}, offset={}", 
                    partition.getPartitionId(),
                    eventData.getOffset(),
                    e);
                
                // Don't checkpoint on error - will be reprocessed
                // Consider moving to dead letter queue after max retries
                handleProcessingError(eventContext, eventData, e);
            }
        };
    }
    
    /**
     * Process Error Consumer
     * 
     * Handles errors at the processor level
     * Examples: Connection failures, partition ownership changes
     */
    private Consumer<ErrorContext> processErrorConsumer() {
        return errorContext -> {
            log.error("Error in Event Hub processor: partition={}", 
                errorContext.getPartitionContext().getPartitionId(),
                errorContext.getThrowable());
            
            // Implement alerting/monitoring here
            // The processor will automatically retry based on retry policy
        };
    }
    
    /**
     * Partition Initialization Consumer
     * 
     * Called when processor starts owning a partition
     * Useful for initialization and monitoring
     */
    private Consumer<InitializationContext> initializationConsumer() {
        return initContext -> {
            log.info("Partition initialized: partitionId={}", 
                initContext.getPartitionContext().getPartitionId());
            
            // Could load partition-specific state here
            // Or emit metrics about partition ownership
        };
    }
    
    /**
     * Partition Close Consumer
     * 
     * Called when processor stops owning a partition
     * Happens during rebalancing or shutdown
     */
    private Consumer<CloseContext> closeConsumer() {
        return closeContext -> {
            log.info("Partition closing: partitionId={}, reason={}", 
                closeContext.getPartitionContext().getPartitionId(),
                closeContext.getCloseReason());
            
            // Cleanup partition-specific resources
            // Final checkpoint happens automatically
        };
    }
    
    /**
     * Handle processing errors
     * 
     * Strategy:
     * 1. Check if event has retry metadata
     * 2. If retries < max: Don't checkpoint, add retry metadata
     * 3. If retries >= max: Move to dead letter queue
     */
    private void handleProcessingError(
        EventContext eventContext,
        EventData eventData,
        Exception error
    ) {
        // Extract retry count from event properties
        Integer retryCount = eventData.getProperties().get("retryCount") != null
            ? Integer.parseInt(eventData.getProperties().get("retryCount").toString())
            : 0;
        
        int maxRetries = 3; // Could be configurable
        
        if (retryCount < maxRetries) {
            log.warn("Event will be retried: retryCount={}, maxRetries={}", 
                retryCount, maxRetries);
            
            // Don't checkpoint - event will be reprocessed
            // In a real implementation, might want to add retry metadata
            
        } else {
            log.error("Max retries exceeded, moving to dead letter: event={}", 
                eventData.getBodyAsString());
            
            // Move to dead letter queue
            eventHandler.handleDeadLetter(
                eventData.getBodyAsString(),
                error.getMessage()
            );
            
            // Checkpoint to prevent reprocessing
            eventContext.updateCheckpoint();
        }
    }
    
    /**
     * Get current checkpoint information
     * 
     * Useful for monitoring lag and processing progress
     */
    public void logCheckpointStatus() {
        // In production, query checkpoint store and emit metrics
        log.info("Consumer is running with consumer group: {}", 
            properties.getConsumerGroup());
    }
    
    /**
     * Stop consuming events
     * 
     * Graceful shutdown:
     * 1. Stop accepting new events
     * 2. Complete in-flight event processing
     * 3. Checkpoint final positions
     * 4. Release partition ownership
     */
    @PreDestroy
    public void stopConsuming() {
        log.info("Stopping Event Hub Consumer");
        
        if (processorClient != null) {
            try {
                // Graceful shutdown with timeout
                processorClient.stop();
                
                // Give it time to checkpoint
                TimeUnit.SECONDS.sleep(5);
                
                log.info("Event Hub Consumer stopped successfully");
            } catch (Exception e) {
                log.error("Error stopping consumer", e);
            }
        }
    }
    
    /**
     * Check if consumer is running
     */
    public boolean isRunning() {
        return processorClient != null && processorClient.isRunning();
    }
}
