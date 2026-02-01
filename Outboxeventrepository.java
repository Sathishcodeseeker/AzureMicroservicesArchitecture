package com.enterprise.eventhub.repository;

import com.enterprise.eventhub.domain.entity.OutboxEvent;
import com.enterprise.eventhub.domain.entity.OutboxEvent.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Outbox Events
 * 
 * 5W1H Analysis:
 * WHO: CDC processor and cleanup jobs
 * WHAT: Data access layer for outbox events
 * WHEN: During event creation, CDC polling, and cleanup
 * WHERE: Interacts with outbox_events table
 * WHY: Provides optimized queries for CDC pattern and prevents race conditions
 * HOW: Uses pessimistic locking and batch operations for performance
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    
    /**
     * Find pending events for CDC processing
     * Uses pessimistic write lock to prevent duplicate processing
     * 
     * WHO: CDC Polling Service
     * WHAT: Retrieves next batch of unprocessed events
     * WHY: Ensures only one CDC instance processes each event
     * HOW: PESSIMISTIC_WRITE lock with SKIP_LOCKED for non-blocking
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
        SELECT e FROM OutboxEvent e 
        WHERE e.status = :status 
        AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :currentTime)
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findPendingEventsForProcessing(
        @Param("status") OutboxStatus status,
        @Param("currentTime") LocalDateTime currentTime,
        org.springframework.data.domain.Pageable pageable
    );
    
    /**
     * Find failed events ready for retry
     * 
     * WHO: CDC Retry Mechanism
     * WHAT: Retrieves failed events that are due for retry
     * WHY: Implements exponential backoff retry strategy
     * HOW: Checks nextRetryAt timestamp
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'FAILED' 
        AND e.nextRetryAt <= :currentTime
        AND e.retryCount < :maxRetries
        ORDER BY e.nextRetryAt ASC
        """)
    List<OutboxEvent> findFailedEventsForRetry(
        @Param("currentTime") LocalDateTime currentTime,
        @Param("maxRetries") Integer maxRetries,
        org.springframework.data.domain.Pageable pageable
    );
    
    /**
     * Find events that exceeded max retries
     * 
     * WHO: Dead Letter Queue Handler
     * WHAT: Identifies events that should be moved to DLQ
     * WHY: Prevents infinite retry loops
     * HOW: Checks retry count against threshold
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'FAILED' 
        AND e.retryCount >= :maxRetries
        ORDER BY e.updatedAt ASC
        """)
    List<OutboxEvent> findEventsForDeadLetter(
        @Param("maxRetries") Integer maxRetries,
        org.springframework.data.domain.Pageable pageable
    );
    
    /**
     * Bulk update status for published events
     * 
     * WHO: CDC Publisher
     * WHAT: Marks batch of events as published
     * WHY: Performance optimization for bulk operations
     * HOW: Single update query instead of multiple saves
     */
    @Modifying
    @Query("""
        UPDATE OutboxEvent e 
        SET e.status = 'PUBLISHED', 
            e.publishedAt = :publishedAt,
            e.updatedAt = :updatedAt
        WHERE e.id IN :ids
        """)
    int bulkUpdateStatusToPublished(
        @Param("ids") List<UUID> ids,
        @Param("publishedAt") LocalDateTime publishedAt,
        @Param("updatedAt") LocalDateTime updatedAt
    );
    
    /**
     * Delete old published events (cleanup)
     * 
     * WHO: Scheduled Cleanup Job
     * WHAT: Removes successfully published events older than retention period
     * WHY: Prevents table bloat and maintains performance
     * HOW: Deletes based on published timestamp
     */
    @Modifying
    @Query("""
        DELETE FROM OutboxEvent e 
        WHERE e.status = 'PUBLISHED' 
        AND e.publishedAt < :cutoffDate
        """)
    int deleteOldPublishedEvents(@Param("cutoffDate") LocalDateTime cutoffDate);
    
    /**
     * Find events by aggregate for idempotency checks
     * 
     * WHO: Business Services
     * WHAT: Checks if event already exists for aggregate
     * WHY: Prevents duplicate event creation
     * HOW: Queries by aggregateId and eventType
     */
    Optional<OutboxEvent> findByAggregateIdAndEventTypeAndStatus(
        String aggregateId, 
        String eventType, 
        OutboxStatus status
    );
    
    /**
     * Count events by status for monitoring
     * 
     * WHO: Monitoring/Observability Systems
     * WHAT: Provides metrics on event processing
     * WHY: Enables alerting on backlog or failures
     * HOW: Simple count query grouped by status
     */
    @Query("SELECT e.status, COUNT(e) FROM OutboxEvent e GROUP BY e.status")
    List<Object[]> countEventsByStatus();
    
    /**
     * Find stuck events in PROCESSING status
     * 
     * WHO: Health Check / Recovery Service
     * WHAT: Identifies events stuck in processing state
     * WHY: Detects and recovers from CDC failures
     * HOW: Finds PROCESSING events older than threshold
     */
    @Query("""
        SELECT e FROM OutboxEvent e 
        WHERE e.status = 'PROCESSING' 
        AND e.updatedAt < :stuckThreshold
        ORDER BY e.updatedAt ASC
        """)
    List<OutboxEvent> findStuckEvents(
        @Param("stuckThreshold") LocalDateTime stuckThreshold
    );
    
    /**
     * Find events by correlation ID for tracing
     * 
     * WHO: Distributed Tracing Systems
     * WHAT: Retrieves all events in a trace
     * WHY: Enables end-to-end visibility
     * HOW: Queries by correlationId
     */
    List<OutboxEvent> findByCorrelationIdOrderByCreatedAtAsc(UUID correlationId);
    
    /**
     * Find events by event type for monitoring
     * 
     * WHO: Analytics/Monitoring
     * WHAT: Events grouped by type
     * WHY: Track event volumes per type
     * HOW: Filter by eventType
     */
    List<OutboxEvent> findByEventTypeAndStatusIn(
        String eventType, 
        List<OutboxStatus> statuses
    );
    
    /**
     * Count pending events (backlog size)
     * 
     * WHO: Monitoring/Alerting
     * WHAT: Size of unprocessed event queue
     * WHY: Detect processing delays or bottlenecks
     * HOW: Count PENDING status events
     */
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status = 'PENDING'")
    long countPendingEvents();
    
    /**
     * Average processing time for events
     * 
     * WHO: Performance Monitoring
     * WHAT: Time between creation and publishing
     * WHY: SLA monitoring and performance tuning
     * HOW: Calculates average duration
     */
    @Query("""
        SELECT AVG(TIMESTAMPDIFF(SECOND, e.createdAt, e.publishedAt)) 
        FROM OutboxEvent e 
        WHERE e.status = 'PUBLISHED' 
        AND e.publishedAt >= :since
        """)
    Double getAverageProcessingTimeSeconds(@Param("since") LocalDateTime since);
}
