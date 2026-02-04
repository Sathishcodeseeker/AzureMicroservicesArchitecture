# Edge Case Analysis: Event-Based Microservices Architecture

**Architecture Overview:**
- Pattern: CQRS (Command Query Responsibility Segregation)
- Event Publishing: Outbox Pattern + CDC (Change Data Capture)
- Message Broker: Azure Event Hub
- Load: ~200 requests/minute (~3.3 req/sec)

---

## 1. Database & Outbox Pattern Edge Cases

### 1.1 Outbox Table Growth & Cleanup
**What Could Go Wrong:**
- Outbox table grows unbounded if cleanup strategy fails
- CDC lag causes old messages to accumulate
- Disk space exhaustion on database server

**Scenarios:**
- CDC processor crashes for extended period (hours/days)
- Cleanup job fails silently due to locks or permissions
- High-frequency events (e.g., 1000 events/sec burst) overflow retention window

**Mitigation Checklist:**
- [ ] Implement partitioning on outbox table by timestamp
- [ ] Set up automated cleanup job with idempotency checks
- [ ] Monitor outbox table size with alerts (e.g., >1M rows)
- [ ] Implement archival strategy for processed events
- [ ] Define TTL policy (e.g., delete after 7 days AND processed=true)

### 1.2 Database Transaction Failures
**What Could Go Wrong:**
- Command updates business entity but outbox insert fails
- Partial commit leaves system in inconsistent state
- Deadlocks between business logic and outbox writes

**Scenarios:**
```
BEGIN TRANSACTION
  UPDATE orders SET status='PAID' WHERE id=123
  INSERT INTO outbox (aggregate_id, event_type, payload)  -- FAILS HERE
COMMIT -- What happens?
```

**Mitigation Checklist:**
- [ ] Ensure outbox insert is in same transaction as business logic
- [ ] Use database constraints to prevent orphaned business data
- [ ] Implement transaction timeout monitoring
- [ ] Add retry logic with exponential backoff for transient failures
- [ ] Log transaction failures for manual reconciliation

### 1.3 CDC Lag & Delay
**What Could Go Wrong:**
- CDC falls behind during peak load (>200 req/min bursts)
- Events published to Event Hub out of order
- Downstream consumers receive stale data

**Scenarios:**
- Database experiencing high CPU/IO load
- CDC connector restarts and replays from old offset
- Network partition between CDC and Event Hub

**Mitigation Checklist:**
- [ ] Monitor CDC lag metrics (e.g., replication lag >30 seconds)
- [ ] Set up alerts for CDC connector failures
- [ ] Implement sequence numbers in event payload
- [ ] Design consumers to handle eventual consistency gracefully
- [ ] Load test CDC at 2-3x normal throughput (600 req/min)

---

## 2. Azure Event Hub Edge Cases

### 2.1 Partition Key Distribution
**What Could Go Wrong:**
- Poor partition key choice creates hot partitions
- Single aggregate generates 80% of events → 1 partition bottleneck
- Head-of-line blocking on slow consumers

**Scenarios:**
- Using tenant_id as partition key where 1 tenant = 70% of traffic
- Using constant partition key (all events to partition 0)
- Partition key based on timestamp (all concurrent events same partition)

**At 200 req/min:**
- With 4 partitions: ~50 req/min per partition (ideal)
- With hot partition: 140 req/min on 1 partition, 20 req/min on others (problem)

**Mitigation Checklist:**
- [ ] Analyze partition key distribution with real data
- [ ] Use composite keys if needed (e.g., `${tenantId}-${hash(orderId)}`)
- [ ] Monitor per-partition throughput metrics
- [ ] Test with burst loads (simulate 600 req/min spike)
- [ ] Consider random partition key for non-ordered events

### 2.2 Throughput Unit Exhaustion
**What Could Go Wrong:**
- Event Hub throttles incoming messages (429 errors)
- CDC retries indefinitely, creating backpressure
- Outbox fills up, database grows uncontrollably

**Event Hub Limits (Standard Tier):**
- 1 TU = 1 MB/sec ingress, 2 MB/sec egress
- At 200 req/min with 5KB avg message = 16.6 KB/sec (~0.016 MB/sec)
- Seems safe, BUT burst scenarios or large payloads can exceed

**Scenarios:**
- Marketing campaign creates 2000 req/min spike
- Large order payloads (50KB each) during checkout rush
- Multiple producers from different services

**Mitigation Checklist:**
- [ ] Enable auto-inflate for throughput units
- [ ] Set up alerts for throttling errors (ServerBusyException)
- [ ] Implement circuit breaker in CDC publisher
- [ ] Test with realistic payload sizes (measure p95, p99)
- [ ] Consider Premium tier for predictable workloads

### 2.3 Event Ordering & Causality
**What Could Go Wrong:**
- Events from same aggregate arrive out of order
- Consumer processes "OrderShipped" before "OrderPaid"
- CQRS read model becomes inconsistent

**Scenarios:**
```
Time T1: OrderCreated (partition 0, offset 100)
Time T2: OrderPaid    (partition 0, offset 101)
Time T3: OrderShipped (partition 0, offset 102)

Consumer receives: T1, T3, T2 (network jitter)
```

**Mitigation Checklist:**
- [ ] Use same partition key for events from same aggregate
- [ ] Include version/sequence number in event payload
- [ ] Implement version checking in event handlers
- [ ] Use Event Hub's sequence number for ordering within partition
- [ ] Design idempotent event handlers
- [ ] Consider event sourcing for strict ordering requirements

### 2.4 Consumer Group & Checkpoint Failures
**What Could Go Wrong:**
- Consumer crashes without checkpointing
- Events reprocessed multiple times
- Duplicate side effects (emails sent twice, payments charged twice)

**Scenarios:**
- Consumer processes 100 events, crashes before checkpoint
- Checkpoint storage (Azure Blob) temporarily unavailable
- Multiple consumer instances fighting over same partition

**Mitigation Checklist:**
- [ ] Implement idempotent event handlers (critical!)
- [ ] Use database-based deduplication (event_id tracking table)
- [ ] Checkpoint frequently (every 10 events or 30 seconds)
- [ ] Use lease-based partition ownership (built-in Event Hub feature)
- [ ] Monitor duplicate event processing metrics

---

## 3. CQRS Pattern Edge Cases

### 3.1 Eventual Consistency Window
**What Could Go Wrong:**
- User creates order, immediately queries → order not found
- Customer sees old status after update
- Support team sees different data than customer

**At 200 req/min with typical delays:**
- Database commit: 10-50ms
- CDC capture: 100-500ms
- Event Hub publish: 50-200ms
- Consumer processing: 100-500ms
- **Total delay: 260ms - 1250ms (p50-p95)**

**Scenarios:**
- User clicks "Submit Order" → redirected to order details page → 404 error
- Admin updates user profile → refresh page → old data still showing
- API returns "Order Created" but read model query returns null

**Mitigation Checklist:**
- [ ] Return write model data in command response (don't force immediate query)
- [ ] Implement "read-your-writes" consistency for same user session
- [ ] Show loading states in UI during consistency window
- [ ] Use cache warming strategies for critical queries
- [ ] Monitor p95/p99 consistency lag metrics
- [ ] Educate users/support about eventual consistency behavior

### 3.2 Read Model Drift & Corruption
**What Could Go Wrong:**
- Bug in event handler creates corrupt read model
- Read model schema change requires full rebuild
- Catastrophic failure requires historical replay

**Scenarios:**
- Event handler has bug for 2 weeks → 400K corrupt records
- Read model database corrupted → need to replay all events
- Schema migration requires reprocessing events from beginning

**Mitigation Checklist:**
- [ ] Maintain event history for replay capability (Event Hub retention)
- [ ] Version event schemas and handlers
- [ ] Implement read model rebuild process (from event log)
- [ ] Use blue-green deployment for read model updates
- [ ] Test event handlers with property-based testing
- [ ] Monitor read model consistency (compare samples with write model)

### 3.3 Command-Side Validation Gaps
**What Could Go Wrong:**
- Query model used for command validation creates race conditions
- Validation passes but constraint violated when writing

**Scenarios:**
```
T1: User A checks if username "john" available → returns false
T2: User B checks if username "john" available → returns false
T3: User A registers with "john" → SUCCESS
T4: User B registers with "john" → SUCCESS (if using read model)
T5: Database constraint violation OR duplicate username
```

**Mitigation Checklist:**
- [ ] Perform critical validation against write model (source of truth)
- [ ] Use optimistic locking with version checks
- [ ] Implement unique constraints in write database
- [ ] Handle constraint violations gracefully in command handler
- [ ] Use read model only for non-critical validations (UX hints)

---

## 4. Data Consistency & Integrity Edge Cases

### 4.1 Duplicate Event Publishing
**What Could Go Wrong:**
- CDC publishes same event multiple times
- Network retry sends event twice
- Consumer processes duplicate events

**Scenarios:**
- CDC crashes after publishing to Event Hub but before updating offset
- Event Hub client retries on timeout (event was already accepted)
- Database trigger fires multiple times for single row change

**Mitigation Checklist:**
- [ ] Use deterministic event IDs (e.g., UUID v5 based on aggregate+version)
- [ ] Implement deduplication in consumers (track processed event IDs)
- [ ] Use "at-least-once" delivery semantics with idempotency
- [ ] Set deduplication window (e.g., 7 days of event IDs in Redis/DB)
- [ ] Monitor duplicate detection metrics

### 4.2 Event Schema Evolution
**What Could Go Wrong:**
- Producer publishes new event schema version
- Old consumers can't deserialize payload
- System downtime during deployment

**Scenarios:**
```
V1: {"orderId": "123", "status": "paid"}
V2: {"orderId": "123", "status": "completed", "paymentMethod": "credit_card"}

Old consumer receiving V2: crash or ignore new field?
```

**Mitigation Checklist:**
- [ ] Use schema registry (Azure Schema Registry for Event Hub)
- [ ] Follow backward/forward compatibility rules
- [ ] Never remove required fields (only add optional fields)
- [ ] Version events in payload (e.g., `"version": "2"`)
- [ ] Deploy consumers before producers when adding fields
- [ ] Test schema compatibility in CI/CD pipeline

### 4.3 Poison Messages & Dead Letter Queue
**What Could Go Wrong:**
- Malformed event payload crashes consumer
- Consumer retries infinitely
- Entire partition blocked by single bad event

**Scenarios:**
- JSON deserialization fails due to encoding issue
- Event references deleted aggregate (orphaned foreign key)
- Consumer business logic throws unhandled exception

**Mitigation Checklist:**
- [ ] Implement dead letter queue for failed events
- [ ] Set max retry count (e.g., 3 attempts)
- [ ] Log detailed error context for debugging
- [ ] Implement circuit breaker for repeated failures
- [ ] Monitor DLQ depth with alerts
- [ ] Create manual replay process for DLQ events

---

## 5. Performance & Scalability Edge Cases

### 5.1 Database Connection Pool Exhaustion
**What Could Go Wrong:**
- 200 req/min means ~3-4 concurrent requests at peak
- Connection pool too small → requests timeout
- Connection leaks gradually exhaust pool

**Scenarios:**
- Default pool size = 10, but peak concurrent = 15
- Long-running queries hold connections for 30+ seconds
- Connection not properly closed after exception

**Mitigation Checklist:**
- [ ] Size connection pool appropriately (min=5, max=20 for this load)
- [ ] Monitor active/idle connection counts
- [ ] Set connection timeout and command timeout
- [ ] Use connection pooling best practices (dispose properly)
- [ ] Load test with 2-3x expected concurrency

### 5.2 Event Hub Consumer Lag
**What Could Go Wrong:**
- Consumer processing slower than producer rate
- Lag grows unbounded
- Events processed hours/days late

**At 200 req/min:**
- 200 events/min = 3.33 events/sec
- If consumer processes 2 events/sec → lag grows by 1.33 events/sec
- After 1 hour: lag = 4,788 events (~80 minutes of backlog)

**Mitigation Checklist:**
- [ ] Monitor consumer lag metrics (EventHub EventsProcessed vs EventsReceived)
- [ ] Alert when lag > 5 minutes worth of events
- [ ] Optimize event handler processing time (target <100ms/event)
- [ ] Scale out consumers (multiple instances per partition)
- [ ] Consider batch processing for higher throughput
- [ ] Profile and optimize slow event handlers

### 5.3 Thundering Herd on Deployment
**What Could Go Wrong:**
- All consumers restart simultaneously
- All try to reconnect and reprocess from checkpoint
- Overwhelm downstream dependencies (read database, external APIs)

**Scenarios:**
- Rolling deployment restarts all 10 consumer instances within 2 minutes
- Each replays last 1000 events from checkpoint
- 10,000 events processed in parallel → database saturated

**Mitigation Checklist:**
- [ ] Implement staggered deployment (e.g., 2 instances at a time)
- [ ] Use health checks with backoff before processing
- [ ] Implement rate limiting in event handlers
- [ ] Use bulkhead pattern to isolate resource pools
- [ ] Test deployment scenario in staging environment

---

## 6. Operational & Monitoring Edge Cases

### 6.1 Silent Failures
**What Could Go Wrong:**
- CDC stops publishing but no alerts fire
- Events pile up in outbox table
- Business notices hours later when queries return stale data

**Scenarios:**
- CDC connector unhealthy but not completely dead
- Event Hub accepts events but consumers are all down
- Outbox cleanup job disabled, table fills disk

**Mitigation Checklist:**
- [ ] Monitor end-to-end latency (command → read model update)
- [ ] Alert on outbox table growth rate
- [ ] Track "time since last event published" metric
- [ ] Implement synthetic transactions (canary events)
- [ ] Monitor CDC connector health endpoint
- [ ] Set up Event Hub consumer lag alerts

### 6.2 Cross-Cutting Concerns Lost in Events
**What Could Go Wrong:**
- Correlation IDs not propagated through event chain
- Cannot trace request through system
- Debugging production issues takes hours

**Scenarios:**
- User reports order issue, logs show thousands of events
- Need to find all events related to specific order across multiple services
- Correlation ID missing from some events in chain

**Mitigation Checklist:**
- [ ] Include correlation/trace ID in all events (standard metadata)
- [ ] Propagate user context, tenant ID, session ID
- [ ] Use structured logging with consistent event fields
- [ ] Implement distributed tracing (Application Insights, Jaeger)
- [ ] Create troubleshooting runbook with query examples

### 6.3 Multi-Tenant Data Leakage
**What Could Go Wrong:**
- Events published without tenant ID
- Consumer processes event for wrong tenant
- Data exposed across tenant boundaries

**Scenarios:**
- Event handler uses global cache instead of tenant-scoped cache
- Read model query missing tenant filter (returns all tenants' data)
- Partition key doesn't include tenant ID → events mixed

**Mitigation Checklist:**
- [ ] Include tenant ID in all events (mandatory field)
- [ ] Validate tenant ID at ingestion and consumption
- [ ] Use tenant-scoped data access layer
- [ ] Implement integration tests for tenant isolation
- [ ] Audit log tenant ID with each event processing

---

## 7. Disaster Recovery Edge Cases

### 7.1 Event Hub Region Outage
**What Could Go Wrong:**
- Azure Event Hub region goes down (rare but possible)
- Cannot publish events for hours
- Outbox table fills up, database disk full

**Mitigation Checklist:**
- [ ] Plan for degraded mode operation (read-only?)
- [ ] Consider geo-replication for Event Hub (Premium tier)
- [ ] Implement disk space monitoring with emergency cleanup
- [ ] Document manual failover procedure
- [ ] Test recovery time objective (RTO) and recovery point objective (RPO)

### 7.2 Mass Event Replay Required
**What Could Go Wrong:**
- Need to rebuild entire read model from scratch
- Must replay millions of events
- Takes days to complete

**Scenarios:**
- Read model database corrupted beyond repair
- Schema change requires reprocessing all historical events
- Bug in consumer created wrong data for 6 months

**Mitigation Checklist:**
- [ ] Configure Event Hub retention (7-90 days, or Premium tier for longer)
- [ ] Implement snapshot strategy for read models (rebuild from snapshot + deltas)
- [ ] Create parallel read model rebuild process (doesn't impact production)
- [ ] Test rebuild process quarterly
- [ ] Consider event archival to cheaper storage (Azure Blob) for long-term replay

### 7.3 Cascading Failures
**What Could Go Wrong:**
- Single component failure triggers chain reaction
- Circuit breakers all open simultaneously
- System enters deadlock state

**Scenarios:**
```
1. Read database slow → consumers slow → lag increases
2. Lag increases → CDC backpressure → write database slow
3. Write database slow → API timeouts → users retry
4. Users retry → more load → database slower
5. Complete system halt
```

**Mitigation Checklist:**
- [ ] Implement bulkheads between components
- [ ] Use different timeouts for each integration point
- [ ] Set up load shedding (reject requests when overloaded)
- [ ] Create chaos engineering tests (Chaos Monkey)
- [ ] Document incident response runbook

---

## 8. Security Edge Cases

### 8.1 Event Payload Injection
**What Could Go Wrong:**
- Malicious payload in event triggers SQL injection in consumer
- XSS attack stored in event, rendered in admin UI
- Secrets accidentally logged in event payload

**Scenarios:**
```json
{
  "orderId": "123",
  "customerName": "'; DROP TABLE orders; --",
  "notes": "<script>alert('xss')</script>"
}
```

**Mitigation Checklist:**
- [ ] Sanitize/validate all event payloads at ingestion
- [ ] Use parameterized queries in event handlers
- [ ] Never log sensitive data (PII, secrets, tokens)
- [ ] Implement content security policy for UIs
- [ ] Scan events for sensitive data patterns (credit cards, SSNs)

### 8.2 Unauthorized Event Publishing
**What Could Go Wrong:**
- Compromised service publishes malicious events
- Events bypass business logic validation
- Audit trail compromised

**Mitigation Checklist:**
- [ ] Authenticate CDC connector to Event Hub (managed identity)
- [ ] Use separate Event Hub namespaces per environment
- [ ] Implement event signing/verification
- [ ] Audit all event publishing with service principal
- [ ] Monitor for anomalous event patterns

---

## Testing Strategy for Edge Cases

### Load Testing Scenarios
1. **Steady state**: 200 req/min for 1 hour
2. **Burst**: 1000 req/min for 5 minutes
3. **Slow ramp**: 0 → 400 req/min over 30 minutes
4. **Spike**: Random spikes 10x normal load

### Chaos Engineering Tests
1. Kill CDC connector mid-processing
2. Simulate Event Hub throttling (429 errors)
3. Introduce 2-second network latency
4. Fill database disk to 95%
5. Restart all consumers simultaneously

### Data Integrity Tests
1. Publish 10K events, verify all in read model
2. Publish duplicate events, verify deduplication
3. Publish events out of order, verify consistency
4. Corrupt event payload, verify DLQ handling

---

## Monitoring Dashboard Essentials

```
Critical Metrics to Track:
- Outbox table row count (alert > 100K)
- CDC lag (alert > 60 seconds)
- Event Hub throughput utilization (alert > 80%)
- Consumer lag per partition (alert > 5 minutes)
- End-to-end latency p95, p99 (alert > 5 seconds)
- Failed event count (alert > 10/minute)
- Dead letter queue depth (alert > 100)
- Database connection pool usage (alert > 80%)
- Duplicate event detection rate
- Read model staleness (sample queries vs write model)
```

---

## Runbook: Incident Response

### Symptom: Events not appearing in read model
1. Check consumer lag metrics
2. Check CDC connector status
3. Check Event Hub ingestion rate
4. Inspect outbox table for stuck events
5. Check dead letter queue

### Symptom: Database connection pool exhausted
1. Identify slow queries in connection pool
2. Check for connection leaks (long-held connections)
3. Temporarily increase pool size
4. Scale out application instances

### Symptom: Event Hub throttling
1. Check current throughput unit usage
2. Enable auto-inflate if not already enabled
3. Analyze burst patterns (need Premium tier?)
4. Review partition key distribution for hot partitions

---

## Recommended Architecture Improvements

Based on edge case analysis:

1. **Implement Inbox Pattern on Consumer Side**: Decouple Event Hub receipt from processing (transactional inbox)

2. **Add Circuit Breakers**: Between all integration points (DB, Event Hub, external APIs)

3. **Implement Saga Pattern**: For distributed transactions requiring rollback/compensation

4. **Add Observability**: OpenTelemetry instrumentation for distributed tracing

5. **Create Shadow Read Model**: Parallel read model for comparison testing and zero-downtime migrations

6. **Implement Feature Flags**: For gradual rollout and quick rollback

7. **Add Rate Limiting**: Protect downstream systems from thundering herd

8. **Setup Automated Chaos Testing**: Weekly automated chaos experiments

---

## Conclusion

At 200 req/min, this architecture is well within safe operating parameters for Azure Event Hub and typical databases. However, the edge cases outlined above become critical during:

- **Burst scenarios** (marketing campaigns, viral content)
- **Failure scenarios** (component outages, network issues)
- **Operational scenarios** (deployments, schema changes)

The key to resilience is:
1. **Idempotency everywhere** (most critical)
2. **Comprehensive monitoring** (detect before users do)
3. **Graceful degradation** (partial functionality > complete failure)
4. **Regular testing** (chaos engineering, load testing)
5. **Clear runbooks** (fast incident resolution)

**Priority Actions** (Do These First):
- [ ] Implement event deduplication in all consumers
- [ ] Set up end-to-end latency monitoring
- [ ] Create outbox table cleanup job with monitoring
- [ ] Load test at 3x normal throughput (600 req/min)
- [ ] Document incident response runbooks
- [ ] Implement distributed tracing with correlation IDs
