package com.enterprise.eventhub.service;

import com.enterprise.eventhub.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox Monitoring Service
 * 
 * 5W1H Analysis:
 * WHO: Operations, DevOps, SRE teams
 * WHAT: Exposes metrics for monitoring and alerting
 * WHEN: Continuous, real-time metrics collection
 * WHERE: Prometheus/Grafana dashboards, alert systems
 * WHY: Enables observability and proactive issue detection
 * HOW: Uses Micrometer for metrics, scheduled updates
 * 
 * Key Metrics:
 * 1. Backlog size (pending events)
 * 2. Processing throughput (events/sec)
 * 3. Error rate
 * 4. Processing latency
 * 5. Dead letter queue size
 */
@Service
@Slf4j
public class OutboxMonitoringService {
    
    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;
    
    // Metric gauges
    private final AtomicLong pendingEventsGauge;
    private final AtomicLong failedEventsGauge;
    private final AtomicLong deadLetterEventsGauge;
    
    // Metric counters
    private final Counter eventsPublishedCounter;
    private final Counter eventsFailedCounter;
    
    public OutboxMonitoringService(
        OutboxEventRepository outboxEventRepository,
        MeterRegistry meterRegistry
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.meterRegistry = meterRegistry;
        
        // Initialize gauges
        this.pendingEventsGauge = new AtomicLong(0);
        this.failedEventsGauge = new AtomicLong(0);
        this.deadLetterEventsGauge = new AtomicLong(0);
        
        // Register gauges
        Gauge.builder("outbox.events.pending", pendingEventsGauge, AtomicLong::get)
            .description("Number of pending events in outbox")
            .register(meterRegistry);
        
        Gauge.builder("outbox.events.failed", failedEventsGauge, AtomicLong::get)
            .description("Number of failed events awaiting retry")
            .register(meterRegistry);
        
        Gauge.builder("outbox.events.dead_letter", deadLetterEventsGauge, AtomicLong::get)
            .description("Number of events in dead letter queue")
            .register(meterRegistry);
        
        // Initialize counters
        this.eventsPublishedCounter = Counter.builder("outbox.events.published")
            .description("Total events successfully published")
            .register(meterRegistry);
        
        this.eventsFailedCounter = Counter.builder("outbox.events.failed.total")
            .description("Total events that failed to publish")
            .register(meterRegistry);
    }
    
    /**
     * Update metrics periodically
     * 
     * Runs every 10 seconds to refresh gauge values
     */
    @Scheduled(fixedRate = 10000)
    public void updateMetrics() {
        try {
            // Get event counts by status
            List<Object[]> statusCounts = outboxEventRepository.countEventsByStatus();
            
            for (Object[] row : statusCounts) {
                String status = row[0].toString();
                Long count = ((Number) row[1]).longValue();
                
                switch (status) {
                    case "PENDING" -> pendingEventsGauge.set(count);
                    case "FAILED" -> failedEventsGauge.set(count);
                    case "DEAD_LETTER" -> deadLetterEventsGauge.set(count);
                }
            }
            
            // Calculate and record average processing time
            Double avgProcessingTime = outboxEventRepository
                .getAverageProcessingTimeSeconds(LocalDateTime.now().minusHours(1));
            
            if (avgProcessingTime != null) {
                meterRegistry.gauge("outbox.processing.latency.avg", avgProcessingTime);
            }
            
        } catch (Exception e) {
            log.error("Error updating metrics", e);
        }
    }
    
    /**
     * Record event published
     */
    public void recordEventPublished() {
        eventsPublishedCounter.increment();
    }
    
    /**
     * Record event failed
     */
    public void recordEventFailed() {
        eventsFailedCounter.increment();
    }
    
    /**
     * Get current backlog size
     */
    public long getBacklogSize() {
        return outboxEventRepository.countPendingEvents();
    }
    
    /**
     * Check health status
     * 
     * Returns unhealthy if:
     * - Backlog > 10,000 events
     * - Dead letter > 100 events
     * - Failed events > 1,000
     */
    public HealthStatus getHealthStatus() {
        long pending = pendingEventsGauge.get();
        long failed = failedEventsGauge.get();
        long deadLetter = deadLetterEventsGauge.get();
        
        if (pending > 10000 || deadLetter > 100 || failed > 1000) {
            return HealthStatus.UNHEALTHY;
        }
        
        if (pending > 5000 || deadLetter > 50 || failed > 500) {
            return HealthStatus.DEGRADED;
        }
        
        return HealthStatus.HEALTHY;
    }
    
    public enum HealthStatus {
        HEALTHY,
        DEGRADED,
        UNHEALTHY
    }
}
