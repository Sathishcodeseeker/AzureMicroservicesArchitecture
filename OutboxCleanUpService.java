package com.enterprise.eventhub.service;

import com.enterprise.eventhub.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Outbox Cleanup Service
 * 
 * 5W1H Analysis:
 * WHO: Scheduled background job
 * WHAT: Removes old published events from outbox table
 * WHEN: Runs on configurable schedule (default: daily at 2 AM)
 * WHERE: Deletes from outbox_events table
 * WHY: Prevents table bloat and maintains query performance
 * HOW: Deletes events older than retention period
 * 
 * Configuration:
 * - outbox.cleanup.enabled: Enable/disable cleanup
 * - outbox.cleanup.retention-days: How long to keep events
 * - outbox.cleanup.cron: When to run cleanup
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupService {
    
    private final OutboxEventRepository outboxEventRepository;
    
    @Value("${outbox.cleanup.enabled:true}")
    private boolean cleanupEnabled;
    
    @Value("${outbox.cleanup.retention-days:7}")
    private int retentionDays;
    
    /**
     * Cleanup old published events
     * 
     * Default: Runs daily at 2 AM
     * Configurable via: outbox.cleanup.cron
     * 
     * Only deletes events with status PUBLISHED
     * Failed and dead letter events are NOT deleted
     */
    @Scheduled(cron = "${outbox.cleanup.cron:0 0 2 * * ?}")
    @Transactional
    public void cleanupOldEvents() {
        if (!cleanupEnabled) {
            log.debug("Cleanup is disabled, skipping");
            return;
        }
        
        log.info("Starting outbox cleanup: retentionDays={}", retentionDays);
        
        try {
            // Calculate cutoff date
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
            
            // Delete old published events
            int deleted = outboxEventRepository.deleteOldPublishedEvents(cutoffDate);
            
            log.info("Cleanup completed: deleted {} events older than {}", 
                deleted, cutoffDate);
            
            // Emit metrics
            if (deleted > 0) {
                log.info("Freed up space by removing {} published events", deleted);
            }
            
        } catch (Exception e) {
            log.error("Error during cleanup", e);
            // Don't propagate - will retry on next schedule
        }
    }
    
    /**
     * Manual cleanup trigger
     * 
     * Useful for operational tasks or testing
     */
    public int cleanupNow(int daysToRetain) {
        log.info("Manual cleanup triggered: retentionDays={}", daysToRetain);
        
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToRetain);
        int deleted = outboxEventRepository.deleteOldPublishedEvents(cutoffDate);
        
        log.info("Manual cleanup completed: deleted {}", deleted);
        
        return deleted;
    }
}
