package com.enterprise.eventhub.service;

import com.enterprise.eventhub.domain.entity.OutboxEvent;
import com.enterprise.eventhub.domain.entity.OutboxEvent.OutboxStatus;
import com.enterprise.eventhub.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * CDC (Change Data Capture) Polling Service
 * 
 * 5W1H Analysis:
 * WHO: Background polling service (scheduled task)
 * WHAT: Pulls pending events from outbox and publishes to Event Hub
 * WHEN: Runs on fixed interval (configurable via outbox.cdc.polling-interval-ms)
 * WHERE: Polls outbox_events table, publishes to Azure Event Hub
 * WHY: Decouples event creation from event publishing for reliability
 * HOW: Polls with pessimistic locking, publishes in batches, updates status
 * 
 * Key Benefits:
 * 1. At-least-once delivery guarantee
 * 2. No dual-write problem
 * 3. Business transaction and event guaranteed atomic
 * 4. Automatic retry with exponential backoff
 * 5. Dead letter queue for poison messages
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxCdcPollingService {
    
    private final OutboxEventRepository outboxEventRepository;
    private final EventHubPublisherService eventHubPublisher;
    
    @Value("${outbox.cdc.batch-size:100}")
    private int batchSize;
    
    @Value("${outbox.cdc.polling-interval-ms:1000}")
    private int pollingIntervalMs;
    
    @Value("${app.event.dlq.max-retry-attempts:3}")
    private int maxRetryAttempts;
    
    /**
     * Main CDC Polling Loop
     * 
     * Process Flow:
     * 1. Query pending events with pessimistic lock
     * 2. Mark events as PROCESSING
     * 3. Publish to Event Hub in batches
     * 4. Update status based on result (PUBLISHED or FAILED)
     * 5. Handle retries and dead letter queue
     * 
     * Scheduled: Fixed delay between executions
     * - Prevents overlapping executions
     * - Ensures one poll completes before next starts
     */
    @Scheduled(fixedDelayString = "${outbox.cdc.polling-interval-ms:1000}")
    public void pollAndPublishEvents() {
        try {
            // Step 1: Fetch pending events
            List<OutboxEvent> pendingEvents = fetchPendingEvents();
            
            if (pendingEvents.isEmpty()) {
                log.trace("No pending events to process");
                return;
            }
            
            log.info("CDC Poll: Found {} pending events", pendingEvents.size());
            
            // Step 2: Mark as processing
            markAsProcessing(pendingEvents);
            
            // Step 3: Publish to Event Hub
            publishEvents(pendingEvents);
            
        } catch (Exception e) {
            log.error("Error in CDC polling cycle", e);
            // Continue to next cycle - don't propagate exception
        }
    }
    
    /**
     * Fetch pending events ready for processing
     * 
     * Uses pessimistic write lock to prevent duplicate processing
     * Only fetches events where:
     * - Status is PENDING
     * - nextRetryAt is null or in the past (for retries)
     */
    @Transactional
    protected List<OutboxEvent> fetchPendingEvents() {
        return outboxEventRepository.findPendingEventsForProcessing(
            OutboxStatus.PENDING,
            LocalDateTime.now(),
            PageRequest.of(0, batchSize)
        );
    }
    
    /**
     * Mark events as PROCESSING
     * 
     * Prevents duplicate processing if multiple CDC instances running
     * Uses optimistic locking (version field) for concurrency control
     */
    @Transactional
    protected void markAsProcessing(List<OutboxEvent> events) {
        events.forEach(event -> {
            event.markAsProcessing();
            outboxEventRepository.save(event);
        });
        
        log.debug("Marked {} events as PROCESSING", events.size());
    }
    
    /**
     * Publish events to Event Hub
     * 
     * Strategy:
     * 1. Group by partition key for ordered processing
     * 2. Publish each group as a batch
     * 3. Handle success/failure per event
     * 4. Update outbox status accordingly
     */
    protected void publishEvents(List<OutboxEvent> events) {
        // Group events by partition key
        var eventsByPartition = events.stream()
            .collect(Collectors.groupingBy(OutboxEvent::getPartitionKey));
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        eventsByPartition.forEach((partitionKey, partitionEvents) -> {
            CompletableFuture<Void> future = publishPartitionBatch(
                partitionKey, 
                partitionEvents
            );
            futures.add(future);
        });
        
        // Wait for all partitions to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .exceptionally(throwable -> {
                log.error("Error publishing events", throwable);
                return null;
            })
            .join();
    }
    
    /**
     * Publish batch for a single partition
     * 
     * All events in a partition are published together
     * Maintains event ordering within the partition
     */
    private CompletableFuture<Void> publishPartitionBatch(
        String partitionKey,
        List<OutboxEvent> events
    ) {
        log.debug("Publishing batch for partition: key={}, count={}", 
            partitionKey, events.size());
        
        // Extract payloads
        List<String> payloads = events.stream()
            .map(OutboxEvent::getPayload)
            .collect(Collectors.toList());
        
        // Publish to Event Hub
        return eventHubPublisher.publishBatch(payloads, partitionKey)
            .thenAccept(publishedCount -> {
                // Success: Mark all as published
                handlePublishSuccess(events);
                log.info("Successfully published batch: partition={}, count={}", 
                    partitionKey, publishedCount);
            })
            .exceptionally(throwable -> {
                // Failure: Mark as failed with retry logic
                handlePublishFailure(events, throwable);
                return null;
            });
    }
    
    /**
     * Handle successful publish
     * 
     * Updates all events in batch to PUBLISHED status
     * Uses bulk update for performance
     */
    @Transactional
    protected void handlePublishSuccess(List<OutboxEvent> events) {
        LocalDateTime now = LocalDateTime.now();
        List<UUID> eventIds = events.stream()
            .map(OutboxEvent::getId)
            .collect(Collectors.toList());
        
        int updated = outboxEventRepository.bulkUpdateStatusToPublished(
            eventIds,
            now,
            now
        );
        
        log.debug("Marked {} events as PUBLISHED", updated);
    }
    
    /**
     * Handle publish failure
     * 
     * Implements retry logic with exponential backoff:
     * - If retries < max: Mark as FAILED, set nextRetryAt
     * - If retries >= max: Move to DEAD_LETTER
     */
    @Transactional
    protected void handlePublishFailure(List<OutboxEvent> events, Throwable error) {
        String errorMessage = error.getMessage();
        
        events.forEach(event -> {
            if (event.getRetryCount() < maxRetryAttempts) {
                // Retry: Mark as failed with exponential backoff
                event.markAsFailed(errorMessage);
                outboxEventRepository.save(event);
                
                log.warn("Event marked for retry: id={}, retryCount={}, nextRetry={}", 
                    event.getId(), event.getRetryCount(), event.getNextRetryAt());
            } else {
                // Max retries exceeded: Move to dead letter
                event.markAsDeadLetter("Max retries exceeded: " + errorMessage);
                outboxEventRepository.save(event);
                
                log.error("Event moved to dead letter: id={}, error={}", 
                    event.getId(), errorMessage);
            }
        });
    }
    
    /**
     * Process failed events for retry
     * 
     * Runs less frequently than main polling
     * Picks up events where nextRetryAt <= now
     */
    @Scheduled(fixedDelayString = "${outbox.cdc.polling-interval-ms:1000}", 
               initialDelay = 5000)
    public void processRetries() {
        try {
            List<OutboxEvent> failedEvents = fetchFailedEventsForRetry();
            
            if (failedEvents.isEmpty()) {
                return;
            }
            
            log.info("Processing retries: count={}", failedEvents.size());
            
            // Reset status to PENDING so main poll picks them up
            resetFailedEventsToPending(failedEvents);
            
        } catch (Exception e) {
            log.error("Error processing retries", e);
        }
    }
    
    /**
     * Fetch failed events ready for retry
     */
    @Transactional
    protected List<OutboxEvent> fetchFailedEventsForRetry() {
        return outboxEventRepository.findFailedEventsForRetry(
            LocalDateTime.now(),
            maxRetryAttempts,
            PageRequest.of(0, batchSize)
        );
    }
    
    /**
     * Reset failed events to PENDING for retry
     */
    @Transactional
    protected void resetFailedEventsToPending(List<OutboxEvent> events) {
        events.forEach(event -> {
            event.setStatus(OutboxStatus.PENDING);
            outboxEventRepository.save(event);
        });
        
        log.debug("Reset {} events to PENDING for retry", events.size());
    }
    
    /**
     * Recover stuck events
     * 
     * Finds events stuck in PROCESSING state
     * Could happen if service crashes mid-processing
     */
    @Scheduled(fixedRate = 60000, initialDelay = 10000) // Every minute
    public void recoverStuckEvents() {
        try {
            // Events stuck in PROCESSING for more than 5 minutes
            LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(5);
            
            List<OutboxEvent> stuckEvents = outboxEventRepository
                .findStuckEvents(stuckThreshold);
            
            if (!stuckEvents.isEmpty()) {
                log.warn("Found {} stuck events, resetting to PENDING", 
                    stuckEvents.size());
                
                resetStuckEventsToPending(stuckEvents);
            }
            
        } catch (Exception e) {
            log.error("Error recovering stuck events", e);
        }
    }
    
    /**
     * Reset stuck events to PENDING
     */
    @Transactional
    protected void resetStuckEventsToPending(List<OutboxEvent> events) {
        events.forEach(event -> {
            event.setStatus(OutboxStatus.PENDING);
            event.setErrorMessage("Recovered from stuck PROCESSING state");
            outboxEventRepository.save(event);
        });
    }
}
