package com.enterprise.eventhub.service;

import com.enterprise.eventhub.domain.entity.OutboxEvent;
import com.enterprise.eventhub.domain.entity.OutboxEvent.OutboxStatus;
import com.enterprise.eventhub.domain.event.BaseEvent;
import com.enterprise.eventhub.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox Service - Transactional Event Publishing
 * 
 * 5W1H Analysis:
 * WHO: Business services creating domain events
 * WHAT: Persists events to outbox table within business transaction
 * WHEN: During any business operation that should produce events
 * WHERE: Called by service layer within @Transactional methods
 * WHY: Ensures atomicity between business state and event creation
 * HOW: Writes to outbox table in same transaction as business logic
 * 
 * Key Pattern: Transactional Outbox
 * - Business logic and event creation share same transaction
 * - If business logic fails, event is not created (rollback)
 * - If business logic succeeds, event is guaranteed to be persisted
 * - CDC then publishes events asynchronously
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {
    
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * Publish event to outbox table
     * 
     * CRITICAL: This method must be called within an existing transaction
     * Propagation.MANDATORY ensures it fails if not in a transaction
     * 
     * @param event Domain event to publish
     * @param aggregateId Business entity ID
     * @param aggregateType Business entity type
     * @return Created outbox event
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publishEvent(BaseEvent event, String aggregateId, String aggregateType) {
        log.debug("Publishing event to outbox: eventType={}, aggregateId={}, aggregateType={}", 
            event.getEventType(), aggregateId, aggregateType);
        
        try {
            // Enrich event with metadata
            enrichEvent(event);
            
            // Serialize event to JSON
            String payload = objectMapper.writeValueAsString(event);
            
            // Create metadata
            String metadata = createMetadata(event);
            
            // Build outbox event
            OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateId(aggregateId)
                .aggregateType(aggregateType)
                .eventType(event.getEventType())
                .payload(payload)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .partitionKey(aggregateId) // Use aggregateId for partitioning
                .correlationId(event.getCorrelationId())
                .metadata(metadata)
                .build();
            
            // Persist to database
            OutboxEvent saved = outboxEventRepository.save(outboxEvent);
            
            log.info("Event published to outbox: eventId={}, outboxId={}", 
                event.getEventId(), saved.getId());
            
            return saved;
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event: {}", event, e);
            throw new OutboxPublishException("Failed to serialize event", e);
        }
    }
    
    /**
     * Publish multiple events in batch
     * Useful for sagas or complex business operations
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishEvents(Map<BaseEvent, EventMetadata> events) {
        events.forEach((event, metadata) -> 
            publishEvent(event, metadata.getAggregateId(), metadata.getAggregateType())
        );
    }
    
    /**
     * Check if event already exists (idempotency)
     */
    @Transactional(readOnly = true)
    public boolean eventExists(String aggregateId, String eventType) {
        return outboxEventRepository
            .findByAggregateIdAndEventTypeAndStatus(aggregateId, eventType, OutboxStatus.PENDING)
            .isPresent() ||
            outboxEventRepository
            .findByAggregateIdAndEventTypeAndStatus(aggregateId, eventType, OutboxStatus.PUBLISHED)
            .isPresent();
    }
    
    /**
     * Enrich event with default values
     */
    private void enrichEvent(BaseEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID());
        }
        if (event.getOccurredAt() == null) {
            event.setOccurredAt(LocalDateTime.now());
        }
        if (event.getCorrelationId() == null) {
            event.setCorrelationId(UUID.randomUUID());
        }
        if (event.getVersion() == null) {
            event.setVersion("1.0");
        }
    }
    
    /**
     * Create metadata JSON
     */
    private String createMetadata(BaseEvent event) throws JsonProcessingException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "outbox-service");
        metadata.put("triggeredBy", event.getTriggeredBy());
        metadata.put("traceId", event.getCorrelationId());
        metadata.put("timestamp", LocalDateTime.now());
        
        return objectMapper.writeValueAsString(metadata);
    }
    
    /**
     * Metadata holder for batch publishing
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class EventMetadata {
        private String aggregateId;
        private String aggregateType;
    }
    
    /**
     * Custom exception for outbox publishing failures
     */
    public static class OutboxPublishException extends RuntimeException {
        public OutboxPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
