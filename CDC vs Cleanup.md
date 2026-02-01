# CDC vs Cleanup Service - Deep Dive

## 🎯 The Core Problem

### Event Lifecycle Visualization

```
┌─────────────────────────────────────────────────────────────────┐
│                    Event Lifecycle                               │
└─────────────────────────────────────────────────────────────────┘

1. PENDING → PROCESSING → PUBLISHED ✅ (STAYS IN DB FOREVER!)
                ↓
2. PENDING → PROCESSING → FAILED → PENDING → ... → DEAD_LETTER ⚠️
                                                    (STAYS FOREVER!)

3. PENDING → PROCESSING → [APP CRASH] 💥 (STUCK IN PROCESSING!)
```

---

## 📊 **What CDC Does (Change Data Capture)**

### CDC Scope: ACTIVE Event Processing

```java
@Scheduled(fixedDelayString = "${outbox.cdc.polling-interval-ms:1000}")
public void pollAndPublishEvents() {
    // CDC ONLY processes these statuses:
    // 1. PENDING
    // 2. FAILED (with next_retry_at <= NOW)
    
    List<OutboxEvent> events = fetchPendingEvents();
    // Query: WHERE status IN ('PENDING', 'FAILED')
    
    publishToEventHub(events);
    updateStatus(events); // → PUBLISHED or FAILED
}
```

### What CDC Does:
- ✅ Picks PENDING events
- ✅ Picks FAILED events (ready for retry)
- ✅ Publishes to Event Hub
- ✅ Updates status to PUBLISHED or FAILED

### What CDC Does NOT Do:
- ❌ Delete PUBLISHED events
- ❌ Delete DEAD_LETTER events
- ❌ Delete old events
- ❌ Clean up the database

---

## 🗑️ **What Cleanup Service Does**

### Cleanup Scope: Database Maintenance

```java
@Scheduled(cron = "${outbox.cleanup.cron:0 0 2 * * ?}") // Daily at 2 AM
@Transactional
public void cleanupOldEvents() {
    // Calculate cutoff: 7 days ago
    LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
    
    // DELETE only PUBLISHED events older than cutoff
    int deleted = outboxEventRepository.deleteOldPublishedEvents(cutoffDate);
    
    log.info("Deleted {} old published events", deleted);
}
```

### SQL Query:
```sql
DELETE FROM outbox_events 
WHERE status = 'PUBLISHED' 
AND published_at < '2025-01-24 00:00:00'  -- 7 days ago
```

### What Cleanup Does:
- ✅ Deletes PUBLISHED events older than retention period
- ✅ Prevents table bloat (millions of rows)
- ✅ Maintains query performance
- ✅ Frees disk space

### What Cleanup Does NOT Do:
- ❌ Delete PENDING events (CDC needs these)
- ❌ Delete FAILED events (still retrying)
- ❌ Delete DEAD_LETTER events (need manual review)
- ❌ Publish events to Event Hub

---

## 🔍 **Why Both Are Needed - Detailed Scenarios**

### **Scenario A: Without Cleanup Service**

#### Day 1: Application Start
```sql
SELECT COUNT(*) FROM outbox_events;
-- Result: 0 rows
```

#### Day 7: After 1 Week (1000 events/day)
```sql
SELECT COUNT(*), status FROM outbox_events GROUP BY status;
-- PUBLISHED: 7,000 rows  ← All successfully published
-- PENDING:   0 rows
-- FAILED:    0 rows
```

#### Day 30: After 1 Month
```sql
SELECT COUNT(*) FROM outbox_events;
-- Result: 30,000 rows (all PUBLISHED)
-- Table size: 500 MB
```

#### Day 365: After 1 Year
```sql
SELECT COUNT(*) FROM outbox_events;
-- Result: 365,000 rows (all PUBLISHED)
-- Table size: 6 GB 💥
```

**Problems:**
1. 💾 **Disk Space Exhaustion**: Table grows indefinitely
2. 🐌 **Slow Queries**: Index scans take longer
3. 💰 **Increased Costs**: More storage = more money
4. 🔥 **Performance Degradation**: Slower CDC queries

```sql
-- CDC Query Performance Degrades Over Time
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
ORDER BY created_at 
LIMIT 100;

-- With 10K rows:   10ms
-- With 100K rows:  50ms
-- With 1M rows:    500ms  ← Too slow!
-- With 10M rows:   5000ms ← Unacceptable!
```

---

### **Scenario B: With Cleanup Service**

#### Day 1-7: Normal Operation
```sql
SELECT COUNT(*), status FROM outbox_events GROUP BY status;
-- PUBLISHED: 7,000 rows
```

#### Day 8: First Cleanup Runs (2 AM)
```sql
-- Cleanup deletes events older than 7 days (Day 1 events)
DELETE FROM outbox_events 
WHERE status = 'PUBLISHED' 
AND published_at < '2025-01-24 02:00:00';

-- Deleted: 1,000 rows (Day 1)

SELECT COUNT(*) FROM outbox_events;
-- Result: 6,000 rows (Days 2-7)
```

#### Day 365: After 1 Year
```sql
SELECT COUNT(*) FROM outbox_events;
-- Result: ~7,000 rows (last 7 days only) ✅
-- Table size: 100 MB (stable)
```

**Benefits:**
1. ✅ **Stable Table Size**: Always ~7,000 rows
2. ✅ **Fast Queries**: Index performance maintained
3. ✅ **Lower Costs**: Minimal storage usage
4. ✅ **Predictable Performance**: Query time stays constant

---

## 🚨 **Specific Scenarios Where Cleanup is Critical**

### **1. High-Volume Systems**

```
Event Rate: 10,000 events/second
Daily Events: 864,000,000 events/day

Without Cleanup (30 days):
- Total rows: 25,920,000,000 rows (26 billion!)
- Table size: ~4 TB
- Query time: 30+ seconds
- Cost: $400/month storage

With Cleanup (7 days):
- Total rows: 6,048,000,000 rows (6 billion)
- Table size: ~1 TB
- Query time: <100ms
- Cost: $100/month storage
```

### **2. Regulatory Compliance**

**GDPR Example:**
```java
// User requests data deletion
public void deleteUserEvents(String userId) {
    // Problem: PUBLISHED events contain user data!
    List<OutboxEvent> userEvents = outboxEventRepository
        .findByPayloadContaining(userId);
    
    // Without cleanup: Events stay forever (GDPR violation!)
    // With cleanup: Events auto-deleted after 7 days ✅
}
```

**Compliance Requirements:**
- 🇪🇺 GDPR: Data retention limits
- 🏥 HIPAA: Medical data retention (typically 6 years, then delete)
- 💳 PCI-DSS: Payment data must be deleted after processing

### **3. Database Partition Management**

**PostgreSQL Table Partitioning:**
```sql
-- Without cleanup: Must manage partitions manually
CREATE TABLE outbox_events_2025_01 PARTITION OF outbox_events 
FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE outbox_events_2025_02 PARTITION OF outbox_events 
FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- With cleanup: Old partitions can be dropped
DROP TABLE outbox_events_2024_12; -- January cleanup
```

### **4. Backup & Restore Performance**

```
Database Backup Time:

Without Cleanup:
- Full backup of 10 billion rows: 8 hours
- Restore time: 12 hours
- Backup size: 2 TB

With Cleanup:
- Full backup of 7 million rows: 10 minutes
- Restore time: 15 minutes
- Backup size: 2 GB
```

---

## 🎯 **Why CDC Cannot Do Cleanup**

### **Technical Reasons:**

#### **1. CDC Focus: Transactional Consistency**
```java
@Transactional
public void pollAndPublishEvents() {
    // CDC transaction scope:
    // 1. SELECT pending events (with lock)
    // 2. UPDATE status to PROCESSING
    // 3. Publish to Event Hub
    // 4. UPDATE status to PUBLISHED
    
    // If we add DELETE here:
    // - Transaction becomes too long
    // - Lock contention increases
    // - Risk of deadlocks
}
```

**Problem with mixing CDC + Cleanup:**
```java
// Bad approach (Don't do this!)
@Transactional
public void pollAndPublishEvents() {
    List<OutboxEvent> events = fetchPendingEvents();
    publishEvents(events);
    updateToPublished(events);
    
    // This causes problems:
    deleteOldPublishedEvents(); // ❌
    
    // Why?
    // 1. Holding locks too long
    // 2. Mixing read/write concerns
    // 3. Unpredictable transaction duration
    // 4. Potential deadlocks with other transactions
}
```

#### **2. Different Execution Frequencies**

```
CDC Frequency:        Every 1 second (86,400 times/day)
Cleanup Frequency:    Once per day (1 time/day)

Ratio:                86,400:1

Problem if combined:
- 86,399 times/day: No cleanup needed (wasted checks)
- 1 time/day: Cleanup needed

Solution: Separate scheduled jobs
```

#### **3. Different Performance Characteristics**

```java
// CDC: Fast, frequent, small batches
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
LIMIT 100;
-- Execution time: 5ms
-- Frequency: Every 1 second

// Cleanup: Slow, infrequent, bulk delete
DELETE FROM outbox_events 
WHERE status = 'PUBLISHED' 
AND published_at < '7 days ago';
-- Execution time: 30 seconds (deleting 1M rows)
-- Frequency: Once per day

// Combining would slow down CDC!
```

#### **4. Index Usage Patterns**

```sql
-- CDC uses this index (optimized for status + created_at)
CREATE INDEX idx_outbox_polling 
ON outbox_events(status, created_at) 
WHERE status IN ('PENDING', 'FAILED');

-- Cleanup uses this index (optimized for published_at)
CREATE INDEX idx_outbox_cleanup 
ON outbox_events(published_at) 
WHERE status = 'PUBLISHED';

-- Different indexes = different query patterns
-- Better to separate the concerns
```

---

## 🏗️ **Architectural Separation**

### **Single Responsibility Principle Applied:**

```
┌─────────────────────────────────────────────────────────────┐
│                   CDC Service                                │
│  Responsibility: Active Event Processing                     │
│  - Poll PENDING events                                       │
│  - Publish to Event Hub                                      │
│  - Update status                                             │
│  - Handle retries                                            │
│  Frequency: Every 1 second                                   │
│  Focus: Real-time event delivery                             │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                Cleanup Service                               │
│  Responsibility: Database Maintenance                        │
│  - Delete old PUBLISHED events                               │
│  - Maintain table size                                       │
│  - Optimize performance                                      │
│  - Ensure compliance                                         │
│  Frequency: Once per day (off-peak hours)                    │
│  Focus: Long-term database health                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 📈 **Performance Impact Analysis**

### **Test Scenario: 1 Million Events/Day**

#### **Without Cleanup (90 days):**
```sql
-- Total events in database
SELECT COUNT(*) FROM outbox_events;
-- Result: 90,000,000 rows

-- CDC query performance
EXPLAIN ANALYZE
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
ORDER BY created_at 
LIMIT 100;

-- Planning Time: 5.234 ms
-- Execution Time: 456.789 ms  ← TOO SLOW!
-- Rows Scanned: 90,000,000
-- Index Scan Cost: HIGH
```

#### **With Cleanup (7-day retention):**
```sql
-- Total events in database
SELECT COUNT(*) FROM outbox_events;
-- Result: 7,000,000 rows

-- CDC query performance
EXPLAIN ANALYZE
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
ORDER BY created_at 
LIMIT 100;

-- Planning Time: 0.123 ms
-- Execution Time: 5.678 ms  ← FAST!
-- Rows Scanned: 7,000,000
-- Index Scan Cost: LOW
```

**Performance Improvement: 80x faster!**

---

## 🛡️ **Handling Edge Cases**

### **Edge Case 1: Cleanup Deletes Event Being Processed**

**Problem:**
```java
// Time: 02:00:00 - Cleanup starts
DELETE FROM outbox_events 
WHERE status = 'PUBLISHED' 
AND published_at < '2025-01-24 02:00:00';

// Time: 02:00:01 - CDC tries to update same event
UPDATE outbox_events 
SET status = 'PUBLISHED' 
WHERE id = '123';  -- Already deleted!
```

**Solution: Race condition prevented by status filter**
```java
// CDC only touches PENDING/FAILED
WHERE status IN ('PENDING', 'FAILED')

// Cleanup only touches PUBLISHED
WHERE status = 'PUBLISHED'

// No overlap = No race condition ✅
```

### **Edge Case 2: Event Published Right Before Cleanup**

**Scenario:**
```
01:59:59 - Event published
02:00:00 - Cleanup runs (7-day cutoff)
02:00:01 - Event is 7 days + 1 second old
```

**Solution: Grace period**
```java
// Add buffer to retention period
LocalDateTime cutoffDate = LocalDateTime.now()
    .minusDays(retentionDays)
    .minusHours(1);  // 1-hour grace period

// Event published at 01:59:59 won't be deleted until tomorrow
```

### **Edge Case 3: Cleanup Fails Mid-Execution**

**Problem:**
```sql
DELETE FROM outbox_events 
WHERE status = 'PUBLISHED' 
AND published_at < '2025-01-24 02:00:00';
-- Deleting 1,000,000 rows...
-- [DATABASE CRASH] after deleting 500,000 rows
```

**Solution: Transactional cleanup**
```java
@Transactional
public void cleanupOldEvents() {
    // Entire delete is atomic
    int deleted = outboxEventRepository.deleteOldPublishedEvents(cutoffDate);
    
    // Either all 1M rows deleted or none
    // No partial cleanup
}
```

---

## 🎯 **Optimized Cleanup Strategy**

### **Batch Deletion for Large Tables**

```java
@Transactional
public void cleanupOldEvents() {
    LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retentionDays);
    
    int batchSize = 10000;
    int totalDeleted = 0;
    int deleted;
    
    do {
        // Delete in small batches to avoid long locks
        deleted = outboxEventRepository.deleteOldPublishedEventsInBatch(
            cutoffDate, 
            batchSize
        );
        
        totalDeleted += deleted;
        
        // Sleep between batches to reduce database load
        Thread.sleep(100);
        
    } while (deleted > 0);
    
    log.info("Cleanup completed: deleted {} events", totalDeleted);
}
```

**SQL Implementation:**
```sql
-- Batch delete with LIMIT
DELETE FROM outbox_events 
WHERE id IN (
    SELECT id FROM outbox_events 
    WHERE status = 'PUBLISHED' 
    AND published_at < '2025-01-24 02:00:00'
    LIMIT 10000
);
```

---

## 📊 **Monitoring Both Services**

### **CDC Metrics:**
```java
// Real-time metrics
outbox.events.pending            // Current backlog
outbox.events.processing.rate    // Events/second
outbox.events.published.total    // Cumulative published
outbox.processing.latency        // Time to publish
```

### **Cleanup Metrics:**
```java
// Daily metrics
outbox.cleanup.events.deleted    // Events deleted
outbox.cleanup.duration          // Cleanup duration
outbox.cleanup.table.size        // Current table size
outbox.cleanup.oldest.event      // Age of oldest event
```

---

## ✅ **Summary: Why We Need Both**

| Aspect | CDC Service | Cleanup Service |
|--------|-------------|-----------------|
| **Purpose** | Event delivery | Database maintenance |
| **Frequency** | Every 1 second | Once per day |
| **Scope** | PENDING, FAILED | PUBLISHED |
| **Duration** | <100ms | 30-60 seconds |
| **Critical** | Yes (core functionality) | Yes (long-term health) |
| **Failure Impact** | Events not delivered | Table bloats |
| **Performance** | Must be fast | Can be slow |
| **Transaction** | Short, frequent | Long, infrequent |

**Both are essential for production systems!**

---

## 🚀 **Best Practices**

1. **CDC:** Run every 1 second for near-real-time delivery
2. **Cleanup:** Run daily at off-peak hours (2-4 AM)
3. **Retention:** 7 days minimum (adjust based on compliance)
4. **Monitoring:** Alert if cleanup fails or table grows unexpectedly
5. **Batch Size:** Delete in batches of 10K for large tables
6. **Indexes:** Maintain separate indexes for CDC and cleanup queries
7. **Testing:** Test both services independently

---

**The key insight: CDC handles the "now" (active events), Cleanup handles the "past" (completed events). Both are needed for a healthy, performant system!**
