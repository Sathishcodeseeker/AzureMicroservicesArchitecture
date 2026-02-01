package com.enterprise.eventhub.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox Entity - Core of the Transactional Outbox Pattern
 * 
 * 5W1H Analysis:
 * WHO: Database and CDC systems
 * WHAT: Stores events before publishing to Event Hub
 * WHEN: Created during business transactions (same DB transaction)
 * WHERE: Primary database with transactional consistency
 * WHY: Ensures atomicity between business logic and event publishing
 * HOW: CDC monitors this table and publishes events to Event Hub
 */
@Entity
@Table(name = "outbox_events", indexes = {
    @Index(name = "idx_outbox_status", columnList = "status"),
    @Index(name = "idx_outbox_created", columnList = "created_at"),
    @Index(name = "idx_outbox_event_type", columnList = "event_type"),
    @Index(name = "idx_outbox_aggregate_id", columnList = "aggregate_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    /**
     * Aggregate ID - Links event to business entity
     * Example: Order ID, User ID, Payment ID
     */
    @Column(nullable = false)
    private String aggregateId;
    
    /**
     * Aggregate Type - Business entity type
     * Example: ORDER, USER, PAYMENT, INVENTORY
     */
    @Column(nullable = false, length = 100)
    private String aggregateType;
    
    /**
     * Event Type - Specific event that occurred
     * Example: OrderCreated, PaymentProcessed, UserRegistered
     */
    @Column(nullable = false, length = 100)
    private String eventType;
    
    /**
     * Payload - JSON representation of the event
     * Contains all necessary data for event consumers
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    
    /**
     * Status - Processing state of the event
     * PENDING: Waiting to be published
     * PROCESSING: Currently being published
     * PUBLISHED: Successfully published to Event Hub
     * FAILED: Publishing failed (will be retried)
     * DEAD_LETTER: Max retries exceeded (moved to DLQ)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;
    
    /**
     * Retry Count - Number of publishing attempts
     * Used for exponential backoff and dead letter decisions
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer retryCount = 0;
    
    /**
     * Error Message - Last error if publishing failed
     * Helps with debugging and monitoring
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * Partition Key - For Event Hub partitioning
     * Ensures event ordering within a partition
     */
    @Column(length = 255)
    private String partitionKey;
    
    /**
     * Correlation ID - For distributed tracing
     * Links events across multiple services
     */
    @Column(length = 100)
    private UUID correlationId;
    
    /**
     * Metadata - Additional context (headers, trace info, etc.)
     */
    @Column(columnDefinition = "TEXT")
    private String metadata;
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    /**
     * Published At - When event was successfully published
     * Used for monitoring and SLA tracking
     */
    @Column
    private LocalDateTime publishedAt;
    
    /**
     * Next Retry At - Scheduled time for next retry
     * Used for exponential backoff implementation
     */
    @Column
    private LocalDateTime nextRetryAt;
    
    @Version
    private Long version; // Optimistic locking for concurrent updates
    
    /**
     * Enum for Outbox Event Status
     */
    public enum OutboxStatus {
        PENDING,      // Initial state, waiting to be processed
        PROCESSING,   // Currently being published to Event Hub
        PUBLISHED,    // Successfully published
        FAILED,       // Publishing failed, will retry
        DEAD_LETTER   // Max retries exceeded, moved to DLQ
    }
    
    /**
     * Mark event as being processed
     * Prevents duplicate processing by CDC
     */
    public void markAsProcessing() {
        this.status = OutboxStatus.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * Mark event as successfully published
     */
    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    /**
     * Mark event as failed and increment retry count
     * Calculates next retry time using exponential backoff
     */
    public void markAsFailed(String errorMessage) {
        this.status = OutboxStatus.FAILED;
        this.errorMessage = errorMessage;
        this.retryCount++;
        this.updatedAt = LocalDateTime.now();
        
        // Exponential backoff: 2^retryCount seconds
        long backoffSeconds = (long) Math.pow(2, retryCount);
        this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
    }
    
    /**
     * Move event to dead letter queue
     */
    public void markAsDeadLetter(String reason) {
        this.status = OutboxStatus.DEAD_LETTER;
        this.errorMessage = reason;
        this.updatedAt = LocalDateTime.now();
    }
}
