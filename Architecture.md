# ARCHITECTURE DEEP DIVE

## Table of Contents
1. [System Architecture](#system-architecture)
2. [Component Details](#component-details)
3. [Data Flow](#data-flow)
4. [NFR Implementation](#nfr-implementation)
5. [Failure Scenarios](#failure-scenarios)
6. [Performance Optimization](#performance-optimization)
7. [Security Considerations](#security-considerations)

---

## 1. System Architecture

### High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Client Layer                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │ Web App  │  │Mobile App│  │  API     │                   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                   │
└───────┼─────────────┼─────────────┼──────────────────────────┘
        │             │             │
        └─────────────┴─────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────────────────┐
│                  API Gateway / Load Balancer                  │
│         (Rate Limiting, Authentication, Routing)              │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                   Application Layer                           │
│  ┌──────────────────────────────────────────────────┐        │
│  │         Spring Boot Application (3 replicas)      │        │
│  │  ┌──────────────┐  ┌──────────────┐             │        │
│  │  │   REST       │  │   Business   │             │        │
│  │  │ Controllers  │→ │   Services   │             │        │
│  │  └──────────────┘  └───────┬──────┘             │        │
│  │                            │                     │        │
│  │                            ▼                     │        │
│  │                    ┌──────────────┐             │        │
│  │                    │   Outbox     │             │        │
│  │                    │   Service    │             │        │
│  │                    └───────┬──────┘             │        │
│  └────────────────────────────┼──────────────────────────────┘
│                                │                              │
└────────────────────────────────┼──────────────────────────────┘
                                 │
                                 ▼
┌──────────────────────────────────────────────────────────────┐
│                    Data Layer                                 │
│  ┌──────────────────────────────────────────┐               │
│  │      PostgreSQL Database (HA Setup)       │               │
│  │  ┌─────────────┐    ┌──────────────┐    │               │
│  │  │   Orders    │    │   Outbox     │    │               │
│  │  │   Table     │    │   Events     │    │               │
│  │  └─────────────┘    └──────────────┘    │               │
│  └──────────────────────────────────────────┘               │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       │ CDC Polling
                       ▼
┌──────────────────────────────────────────────────────────────┐
│              CDC & Publishing Layer                           │
│  ┌──────────────────────────────────────────────────┐        │
│  │            CDC Polling Service                    │        │
│  │  (Scheduled Task, Pessimistic Locking)           │        │
│  └────────────────────┬─────────────────────────────┘        │
│                       │                                       │
│                       ▼                                       │
│  ┌──────────────────────────────────────────────────┐        │
│  │        Event Hub Publisher Service                │        │
│  │  ┌────────────┐ ┌─────────────┐ ┌────────────┐  │        │
│  │  │  Circuit   │ │   Retry     │ │   Rate     │  │        │
│  │  │  Breaker   │ │   Logic     │ │  Limiter   │  │        │
│  │  └────────────┘ └─────────────┘ └────────────┘  │        │
│  └────────────────────┬─────────────────────────────┘        │
└───────────────────────┼──────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────────┐
│                 Azure Event Hub                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │Partition │  │Partition │  │Partition │  │Partition │    │
│  │    0     │  │    1     │  │    2     │  │    3     │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
└──────────────────────┬───────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────┐
│              Consumption Layer                                │
│  ┌──────────────────────────────────────────────────┐        │
│  │        Event Hub Consumer Service                 │        │
│  │  (Multiple Instances, Load Balanced)              │        │
│  └────────────────────┬─────────────────────────────┘        │
│                       │                                       │
│                       ▼                                       │
│  ┌──────────────────────────────────────────────────┐        │
│  │           Event Handlers                          │        │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐   │        │
│  │  │ Order  │ │Payment │ │Invntry │ │  User  │   │        │
│  │  │Handler │ │Handler │ │Handler │ │Handler │   │        │
│  │  └────────┘ └────────┘ └────────┘ └────────┘   │        │
│  └──────────────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────┐
│           Downstream Services / Systems                       │
│  (Email, Analytics, Warehouses, Notifications, etc.)         │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│           Observability Layer (Cross-Cutting)                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │Prometheus│  │ Grafana  │  │  Jaeger  │  │   ELK    │    │
│  │ Metrics  │  │Dashboards│  │ Tracing  │  │  Logs    │    │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘    │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. Component Details

### 2.1 REST Controllers

**Responsibilities:**
- HTTP request/response handling
- Input validation
- DTO transformation
- Error handling
- API versioning

**Key Patterns:**
- Circuit Breaker: Protects against downstream failures
- Rate Limiting: Prevents API abuse
- Validation: Bean Validation (JSR-380)

**Example Flow:**
```
POST /api/v1/orders
  ↓
OrderController.createOrder()
  ↓ @Valid
Validate CreateOrderRequestDto
  ↓ @CircuitBreaker
Call OrderService.createOrder()
  ↓ @RateLimiter
Rate limit check
  ↓
Return OrderResponse
```

### 2.2 Business Services

**OrderService Responsibilities:**
- Business logic execution
- Transaction management
- Event creation
- Validation rules

**Transaction Boundary:**
```java
@Transactional  // ← Transaction starts here
public Order createOrder(CreateOrderRequest request) {
    // 1. Validate
    validateOrder(request);
    
    // 2. Save Order
    Order order = orderRepository.save(order);
    
    // 3. Publish Event (SAME transaction)
    outboxService.publishEvent(event, orderId, "ORDER");
    
    // Transaction commits here ↓
    return order;
}
```

**Key Point:** Both order and event are saved in the SAME database transaction. Either both succeed or both rollback.

### 2.3 Outbox Service

**Responsibilities:**
- Event serialization (Java → JSON)
- Metadata creation
- Idempotency checks
- Outbox event persistence

**Critical Configuration:**
```java
@Transactional(propagation = Propagation.MANDATORY)
```
This ensures publishEvent() MUST be called within an existing transaction. It cannot create its own transaction.

**Why MANDATORY?**
- Prevents accidental standalone event creation
- Enforces transactional consistency
- Makes the pattern explicit in code

### 2.4 CDC Polling Service

**Responsibilities:**
- Poll outbox_events table periodically
- Lock events for processing
- Batch events by partition
- Publish to Event Hub
- Update event status
- Handle retries

**Polling Strategy:**
```sql
SELECT * FROM outbox_events 
WHERE status = 'PENDING' 
AND (next_retry_at IS NULL OR next_retry_at <= NOW())
ORDER BY created_at ASC
FOR UPDATE SKIP LOCKED  -- Pessimistic locking
LIMIT 100;
```

**FOR UPDATE SKIP LOCKED:**
- Locks selected rows
- Other CDC instances skip locked rows
- Prevents duplicate processing
- Non-blocking (SKIP LOCKED)

**Processing Loop:**
```
Every 1 second:
  1. Fetch pending events (with lock)
  2. Mark as PROCESSING
  3. Group by partition key
  4. Publish each partition batch
  5. On success: Mark PUBLISHED
  6. On failure: Mark FAILED, set next_retry_at
  7. Release lock (transaction commit)
```

### 2.5 Event Hub Publisher

**Responsibilities:**
- Batch creation
- Partition routing
- Resilience patterns
- Error handling
- Metrics emission

**Resilience Stack:**
```
Request
  ↓
@RateLimiter (100 req/sec)
  ↓
@Bulkhead (25 concurrent)
  ↓
@CircuitBreaker (50% failure threshold)
  ↓
@Retry (3 attempts, exponential backoff)
  ↓
Azure Event Hub
```

**Backpressure Handling:**
```java
// Split large batches
EventDataBatch currentBatch = createBatch();

for (EventData event : events) {
    if (!currentBatch.tryAdd(event)) {
        // Batch full, send it
        send(currentBatch);
        
        // Create new batch
        currentBatch = createBatch();
        currentBatch.tryAdd(event);
    }
}
```

### 2.6 Event Hub Consumer

**Responsibilities:**
- Subscribe to Event Hub
- Partition management
- Checkpoint management
- Event deserialization
- Error handling

**Partition Processing:**
```
Event Hub: 4 partitions
Consumer Instances: 2

Auto load balancing:
Instance 1: Partitions 0, 1
Instance 2: Partitions 2, 3

If Instance 1 fails:
Instance 2: All partitions (0, 1, 2, 3)

When Instance 1 recovers:
Rebalance occurs automatically
```

**Checkpoint Strategy:**
```
Process Event:
  1. Receive from partition
  2. Deserialize JSON
  3. Route to handler
  4. Execute business logic
  5. On success: Checkpoint
  6. On failure: Don't checkpoint (will reprocess)
```

### 2.7 Event Handlers

**Responsibilities:**
- Event type routing
- Business logic execution
- Error handling
- Idempotency

**Handler Pattern:**
```java
public boolean handleOrderCreated(OrderCreatedEvent event) {
    try {
        // Idempotency check
        if (alreadyProcessed(event.getEventId())) {
            return true;  // Already processed
        }
        
        // Business logic
        updateReadModel(event);
        sendNotification(event);
        
        // Mark as processed
        markProcessed(event.getEventId());
        
        return true;  // Success
    } catch (Exception e) {
        log.error("Failed to process", e);
        return false;  // Will retry
    }
}
```

---

## 3. Data Flow

### 3.1 Write Path (Event Publishing)

```
1. HTTP Request
   POST /api/v1/orders
   Body: { customerId: "...", amount: 99.99, ... }

2. Controller Layer
   OrderController.createOrder()
   - Validate request DTO
   - Apply rate limiting
   - Circuit breaker check

3. Service Layer
   @Transactional  // ← Transaction begins
   OrderService.createOrder()
   - Validate business rules
   - Create Order entity
   - Save to orders table
   
4. Outbox Layer
   OutboxService.publishEvent()
   - Serialize event to JSON
   - Create OutboxEvent entity
   - Save to outbox_events table
   // ← Transaction commits (both saves atomic)

5. CDC Layer
   OutboxCdcPollingService (scheduled)
   - Poll: SELECT ... FROM outbox_events WHERE status='PENDING'
   - Lock: FOR UPDATE SKIP LOCKED
   - Mark: UPDATE status='PROCESSING'
   - Publish to Event Hub
   - Update: status='PUBLISHED'

6. Event Hub
   - Event stored in partition
   - Replicated across zones
   - Available to consumers
```

### 3.2 Read Path (Event Consumption)

```
1. Event Hub Consumer
   EventProcessorClient
   - Subscribe to partitions
   - Receive events in batches
   - Load balanced across instances

2. Event Handler
   processEventConsumer()
   - Deserialize EventData
   - Extract event type
   - Route to specific handler

3. Type-Specific Handler
   OrderEventHandler.handleOrderCreated()
   - Execute business logic
   - Update read models
   - Send notifications
   - Call downstream services

4. Checkpoint
   eventContext.updateCheckpoint()
   - Mark position in partition
   - Stored in blob storage
   - Used for recovery
```

### 3.3 Retry Flow

```
1. Initial Failure
   Event publish fails
   - Network timeout
   - Event Hub unavailable
   - Authentication error

2. Mark for Retry
   OutboxEvent.markAsFailed()
   - Increment retryCount
   - Set errorMessage
   - Calculate next_retry_at = now + 2^retryCount seconds
   
   Retry Schedule:
   - Attempt 1: Immediate
   - Attempt 2: +2 seconds
   - Attempt 3: +4 seconds
   - Attempt 4: +8 seconds

3. Retry Processing
   CDC Polling picks up failed events
   WHERE status='FAILED' AND next_retry_at <= NOW()
   
4. Outcomes
   Success: Mark PUBLISHED
   Failure (retries < max): Mark FAILED, increment retry
   Failure (retries >= max): Mark DEAD_LETTER
```

---

## 4. NFR Implementation

### 4.1 Availability

**Target:** 99.9% uptime

**Implementation:**
- Multiple application instances (3+)
- Database HA (primary + replicas)
- Event Hub (multi-zone replication)
- Health checks and auto-restart
- Circuit breaker prevents cascading failures

**Deployment:**
```yaml
replicas: 3
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 1
    maxSurge: 1
```

### 4.2 Scalability

**Target:** Handle 10,000 TPS

**Horizontal Scaling:**
- Add more application instances
- Database read replicas
- Event Hub partitions (scale out)
- Consumer instances scale with partitions

**Vertical Scaling:**
- Increase database resources
- Larger Event Hub throughput units

**Configuration:**
```yaml
# HPA (Horizontal Pod Autoscaler)
minReplicas: 3
maxReplicas: 10
targetCPUUtilizationPercentage: 70
```

### 4.3 Performance

**Target Latencies:**
- API Response: p95 < 200ms
- Event Publishing: p95 < 500ms
- Event Processing: p95 < 1s

**Optimizations:**
- Batch operations (reduce DB calls)
- Connection pooling (HikariCP)
- Async processing (CompletableFuture)
- Indexes on outbox queries
- Bulk updates for status changes

**Database Tuning:**
```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 30000
```

### 4.4 Reliability

**Target:** 99.99% event delivery

**Mechanisms:**
- Transactional outbox (atomicity)
- At-least-once delivery
- Retry with exponential backoff
- Dead letter queue
- Idempotency in consumers

### 4.5 Observability

**Metrics:**
- Backlog size (pending events)
- Processing throughput (events/sec)
- Error rate
- Latency (p50, p95, p99)
- Circuit breaker state

**Logging:**
- Structured logging (JSON)
- Correlation IDs
- Request tracing
- Error stack traces

**Dashboards:**
- Real-time event flow
- System health
- Error trends
- Performance graphs

### 4.6 Security

**Authentication:**
- API key authentication
- Azure AD for Event Hub
- Database credentials in secrets

**Authorization:**
- Role-based access control
- Service accounts with least privilege

**Data Protection:**
- TLS in transit
- Encryption at rest (Azure)
- PII masking in logs
- Sensitive data in secrets

### 4.7 Disaster Recovery

**RTO:** 4 hours
**RPO:** 1 hour

**Backup Strategy:**
- Database: Daily backups, 30-day retention
- Event Hub: Built-in replication
- Application: Stateless, quick recovery

**Recovery Procedure:**
1. Restore database from backup
2. Replay events from Event Hub (if needed)
3. Redeploy application
4. Verify data consistency

---

## 5. Failure Scenarios

### 5.1 Database Failure

**Scenario:** PostgreSQL becomes unavailable

**Impact:**
- New orders cannot be created
- CDC polling stops
- Existing events in Event Hub continue processing

**Mitigation:**
- Database HA with automatic failover
- Circuit breaker opens quickly
- Graceful degradation (return 503)
- Health checks detect and restart

**Recovery:**
1. Automatic failover to replica
2. Application reconnects
3. Circuit breaker closes
4. Normal operation resumes

### 5.2 Event Hub Failure

**Scenario:** Azure Event Hub unavailable

**Impact:**
- Events accumulate in outbox table
- Backlog increases
- CDC continues polling but publishing fails

**Mitigation:**
- Circuit breaker opens after threshold
- Retry with exponential backoff
- Alerts trigger when backlog > threshold
- Events safely stored in database

**Recovery:**
1. Event Hub becomes available
2. Circuit breaker transitions to half-open
3. Successful calls close circuit
4. CDC processes backlog
5. System catches up

### 5.3 Application Crash

**Scenario:** Application instance crashes mid-processing

**Impact:**
- Some events stuck in PROCESSING state
- Other instances continue working
- Load balancer routes traffic away

**Mitigation:**
- Stuck event recovery job
- Resets PROCESSING → PENDING after timeout
- Multiple instances provide redundancy
- Kubernetes restarts crashed pod

**Recovery:**
```
Every minute:
  Find events:
    status = 'PROCESSING'
    updated_at < (NOW - 5 minutes)
  Reset to:
    status = 'PENDING'
  CDC will reprocess
```

### 5.4 Network Partition

**Scenario:** Network split between app and database

**Impact:**
- Transactions fail
- Circuit breaker opens
- API returns 503

**Mitigation:**
- Short connection timeout
- Fast failure detection
- Retry after recovery
- No data loss (transaction rollback)

**Recovery:**
- Network heals
- Connection pool reconnects
- Circuit breaker closes
- Normal operation

### 5.5 Poison Message

**Scenario:** Event that always fails processing

**Impact:**
- Consumer keeps retrying
- Blocks partition processing

**Mitigation:**
- Retry limit (3 attempts)
- Dead letter queue
- Alert on DLQ events
- Manual investigation

**Flow:**
```
Event fails → Retry 1
Event fails → Retry 2
Event fails → Retry 3
Event fails → Dead Letter Queue
Alert triggered → Manual review
```

---

## 6. Performance Optimization

### 6.1 Database Optimizations

**Indexes:**
```sql
-- Frequently queried columns
CREATE INDEX idx_outbox_status ON outbox_events(status);
CREATE INDEX idx_outbox_created ON outbox_events(created_at);

-- Composite for CDC query
CREATE INDEX idx_outbox_polling 
ON outbox_events(status, created_at) 
WHERE status IN ('PENDING', 'FAILED');

-- Partial index for retries
CREATE INDEX idx_outbox_next_retry 
ON outbox_events(next_retry_at) 
WHERE next_retry_at IS NOT NULL;
```

**Batch Operations:**
```java
// Instead of N updates
for (UUID id : eventIds) {
    outboxRepo.updateStatus(id, "PUBLISHED");
}

// Use bulk update
outboxRepo.bulkUpdateStatusToPublished(eventIds);
```

**Connection Pooling:**
```yaml
hikari:
  maximum-pool-size: 20      # Max connections
  minimum-idle: 5            # Idle connections
  connection-timeout: 30000  # 30 seconds
  max-lifetime: 1800000      # 30 minutes
```

### 6.2 Event Hub Optimizations

**Batching:**
```java
// Batch events for throughput
EventDataBatch batch = createBatch();
for (EventData event : events) {
    batch.tryAdd(event);
}
send(batch);  // Single network call
```

**Partitioning:**
```java
// Use partition key for ordering
CreateBatchOptions options = new CreateBatchOptions()
    .setPartitionKey(orderId);  // Events for same order → same partition
```

**Prefetching:**
```yaml
prefetchCount: 300  # Prefetch events for consumer
```

### 6.3 Application Optimizations

**Async Processing:**
```java
// Non-blocking event publishing
CompletableFuture<Void> future = 
    eventHubPublisher.publishBatch(events, partitionKey);

// Process other work while waiting
doOtherWork();

// Wait only when needed
future.join();
```

**Caching:**
```java
// Cache frequently accessed data
@Cacheable("eventTypes")
public List<EventType> getEventTypes() {
    return repository.findAll();
}
```

**Resource Limits:**
```yaml
bulkhead:
  maxConcurrentCalls: 25  # Limit concurrent Event Hub calls
  maxWaitDuration: 100ms  # Max wait for semaphore
```

---

## 7. Security Considerations

### 7.1 Authentication & Authorization

**API Authentication:**
```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/actuator/health").permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt())
            .build();
    }
}
```

**Event Hub Authentication:**
```yaml
# Connection string with SAS token
connection-string: Endpoint=sb://...;SharedAccessKeyName=...;SharedAccessKey=...

# Or Azure AD (recommended)
azure:
  credential:
    client-id: ${AZURE_CLIENT_ID}
    tenant-id: ${AZURE_TENANT_ID}
    client-secret: ${AZURE_CLIENT_SECRET}
```

### 7.2 Data Protection

**Sensitive Data Masking:**
```java
@JsonIgnore
private String creditCardNumber;

// Or custom serializer
@JsonSerialize(using = SensitiveDataSerializer.class)
private String ssn;
```

**Encryption:**
- TLS 1.3 for all network traffic
- Azure Storage encryption at rest
- Database encryption at rest
- Secrets in Azure Key Vault

### 7.3 Input Validation

**Bean Validation:**
```java
public class CreateOrderRequest {
    
    @NotBlank
    @Size(max = 255)
    private String customerId;
    
    @NotNull
    @DecimalMin("0.01")
    @DecimalMax("999999.99")
    private Double totalAmount;
    
    @Pattern(regexp = "^[A-Z]{3}$")
    private String currency;
}
```

**SQL Injection Prevention:**
- Use JPA/Hibernate (ORM)
- Parameterized queries
- No string concatenation in SQL

### 7.4 Rate Limiting

**API Level:**
```yaml
resilience4j:
  ratelimiter:
    instances:
      orderService:
        limitForPeriod: 100        # 100 requests
        limitRefreshPeriod: 1s     # per second
        timeoutDuration: 500ms     # wait max 500ms
```

**IP-based:**
```java
@Component
public class IpRateLimiter {
    
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();
    
    public boolean allowRequest(String ipAddress) {
        RateLimiter limiter = limiters.computeIfAbsent(
            ipAddress,
            ip -> RateLimiter.of(ip, RateLimiterConfig.ofDefaults())
        );
        return limiter.acquirePermission();
    }
}
```

---

**End of Architecture Document**

This architecture provides:
✅ High availability and fault tolerance
✅ Guaranteed event delivery
✅ Scalability to 10,000+ TPS
✅ Sub-second latency
✅ Comprehensive observability
✅ Security best practices
✅ Disaster recovery capabilities
