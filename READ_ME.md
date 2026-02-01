# Azure Event Hub with Transactional Outbox Pattern

A production-ready Spring Boot application implementing the Transactional Outbox Pattern with Azure Event Hub, featuring comprehensive NFR implementations including Circuit Breaker, Retry, Rate Limiting, Backpressure, and CDC (Change Data Capture).

## 📋 Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [5W1H Analysis](#5w1h-analysis)
- [Key Features](#key-features)
- [Technology Stack](#technology-stack)
- [Setup Instructions](#setup-instructions)
- [Configuration](#configuration)
- [Usage Examples](#usage-examples)
- [Monitoring & Observability](#monitoring--observability)
- [Deployment](#deployment)

---

## 🎯 Overview

This application demonstrates enterprise-grade event-driven architecture using the **Transactional Outbox Pattern** to guarantee exactly-once event delivery between microservices via Azure Event Hub.

### The Problem It Solves

**Dual-Write Problem**: When you need to:
1. Update your database (e.g., create an order)
2. Publish an event to a message broker (e.g., OrderCreated)

Traditional approach risks:
- ✗ Database succeeds, event publish fails → Lost event
- ✗ Event publishes, database fails → Phantom event
- ✗ Network partition between operations → Inconsistency

**Solution**: Transactional Outbox Pattern
- ✓ Write business data + event to database in ONE transaction
- ✓ Separate CDC process publishes events asynchronously
- ✓ Guarantees at-least-once delivery
- ✓ No dual-write problem

---

## 🏗️ Architecture

```
┌─────────────┐      ┌──────────────────┐      ┌─────────────────┐
│   REST API  │─────▶│  Order Service   │─────▶│   Orders DB     │
└─────────────┘      │ (Business Logic) │      │  + Outbox Table │
                     └──────────────────┘      └─────────────────┘
                              │                         │
                              │ Same Transaction        │
                              ▼                         │
                     ┌──────────────────┐              │
                     │  Outbox Service  │              │
                     │ (Event Creation) │              │
                     └──────────────────┘              │
                                                        │
                     ┌──────────────────┐              │
                     │  CDC Polling     │◀─────────────┘
                     │    Service       │   Poll pending events
                     └──────────────────┘
                              │
                              │ Publish
                              ▼
                     ┌──────────────────┐
                     │  Event Hub       │
                     │   Publisher      │
                     │ (Circuit Breaker,│
                     │  Retry, Backpr.) │
                     └──────────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │ Azure Event Hub  │
                     └──────────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │  Event Hub       │
                     │   Consumer       │
                     └──────────────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │  Event Handlers  │
                     │  (Process Events)│
                     └──────────────────┘
```

---

## 📊 5W1H Analysis

### **WHO** uses this system?
- **Developers**: Building event-driven microservices
- **DevOps/SRE**: Operating and monitoring the system
- **Business Systems**: Consuming events for downstream processing
- **End Users**: Indirectly benefit from reliable event processing

### **WHAT** does it do?
- Guarantees reliable event delivery to Azure Event Hub
- Implements transactional outbox pattern for atomicity
- Provides CDC (Change Data Capture) for event publishing
- Handles all NFRs: resilience, monitoring, backpressure

### **WHEN** is it used?
- When creating/updating business entities (orders, payments, users)
- Continuous CDC polling (configurable interval)
- Event consumption in real-time
- Scheduled cleanup of old events

### **WHERE** does it run?
- Kubernetes/Cloud environments
- Azure infrastructure
- Connects to PostgreSQL database
- Publishes to Azure Event Hub

### **WHY** is it needed?
- Prevent data inconsistency from dual-writes
- Guarantee event delivery (at-least-once)
- Enable event-driven architecture
- Decouple services via events
- Meet enterprise NFRs (resilience, observability)

### **HOW** does it work?

#### Event Publishing Flow:
1. **Request**: REST API receives order creation request
2. **Business Logic**: OrderService validates and creates order
3. **Atomic Write**: In single transaction:
   - Save order to `orders` table
   - Save event to `outbox_events` table
4. **CDC Polling**: Background service polls `outbox_events`
5. **Publish**: Events published to Azure Event Hub with resilience
6. **Update**: Mark events as PUBLISHED or FAILED
7. **Retry**: Failed events retried with exponential backoff
8. **Cleanup**: Old published events removed periodically

#### Event Consumption Flow:
1. **Subscribe**: Consumer subscribes to Event Hub
2. **Receive**: Events received from partitions
3. **Process**: Event handlers process by type
4. **Checkpoint**: Progress checkpointed on success
5. **Retry**: Failed events reprocessed
6. **DLQ**: Poison messages moved to dead letter queue

---

## ✨ Key Features

### 1. **Transactional Outbox Pattern**
- ✅ Atomic writes (business data + events)
- ✅ No dual-write problem
- ✅ Guaranteed event delivery
- ✅ Idempotency support

### 2. **Change Data Capture (CDC)**
- ✅ Polling-based CDC implementation
- ✅ Pessimistic locking prevents duplicates
- ✅ Batch processing for efficiency
- ✅ Configurable polling interval

### 3. **Circuit Breaker**
- ✅ Prevents cascading failures
- ✅ Automatic state transitions (Closed → Open → Half-Open)
- ✅ Configurable thresholds
- ✅ Fallback mechanisms

### 4. **Retry with Exponential Backoff**
- ✅ Automatic retry on transient failures
- ✅ Exponential backoff: 2^retryCount seconds
- ✅ Maximum retry attempts
- ✅ Dead letter queue for poison messages

### 5. **Rate Limiting**
- ✅ Prevents overwhelming downstream systems
- ✅ Token bucket algorithm
- ✅ Configurable limits
- ✅ Per-service configuration

### 6. **Bulkhead**
- ✅ Resource isolation
- ✅ Limits concurrent calls
- ✅ Prevents resource exhaustion
- ✅ Configurable thread pool size

### 7. **Backpressure Handling**
- ✅ Batch size limits
- ✅ Prefetch count control
- ✅ Prevents memory overflow
- ✅ Automatic slowdown

### 8. **Monitoring & Observability**
- ✅ Prometheus metrics
- ✅ Health checks
- ✅ Real-time dashboards
- ✅ Alerting capabilities

### 9. **Event Consumption**
- ✅ Partition-based processing
- ✅ Checkpoint management
- ✅ Load balancing across consumers
- ✅ At-least-once delivery

### 10. **Automatic Cleanup**
- ✅ Scheduled removal of old events
- ✅ Prevents table bloat
- ✅ Configurable retention period
- ✅ Maintains performance

---

## 🛠️ Technology Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Framework | Spring Boot 3.2.0 | Application framework |
| Language | Java 17 | Programming language |
| Database | PostgreSQL 15+ | Data persistence |
| Messaging | Azure Event Hub | Event streaming |
| Resilience | Resilience4j | Circuit breaker, retry, rate limiting |
| Metrics | Micrometer + Prometheus | Observability |
| Migration | Flyway | Database versioning |
| Serialization | Jackson | JSON processing |
| Testing | JUnit 5 + Testcontainers | Testing framework |

---

## 🚀 Setup Instructions

### Prerequisites

- Java 17+
- Maven 3.8+
- PostgreSQL 15+
- Azure Event Hub namespace
- Azure Storage Account (for checkpoints)

### 1. Clone Repository

```bash
git clone <repository-url>
cd eventhub-outbox
```

### 2. Configure Database

Create PostgreSQL database:

```sql
CREATE DATABASE outboxdb;
CREATE USER outbox_user WITH PASSWORD 'outbox_pass';
GRANT ALL PRIVILEGES ON DATABASE outboxdb TO outbox_user;
```

### 3. Configure Azure Event Hub

Create Event Hub resources:
1. Event Hub namespace
2. Event Hub (e.g., "events")
3. Consumer group (e.g., "$Default")
4. Storage account for checkpoints
5. Blob container (e.g., "checkpoints")

### 4. Configure Application

Edit `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/outboxdb
    username: outbox_user
    password: outbox_pass

azure:
  eventhub:
    namespace: your-namespace
    connection-string: Endpoint=sb://your-namespace.servicebus.windows.net/;...
    event-hub-name: events
    consumer-group: $Default
    checkpoint-store:
      connection-string: DefaultEndpointsProtocol=https;AccountName=...
      container-name: checkpoints
```

### 5. Build Application

```bash
mvn clean install
```

### 6. Run Database Migrations

```bash
mvn flyway:migrate
```

### 7. Run Application

```bash
mvn spring-boot:run
```

Or run JAR:

```bash
java -jar target/eventhub-outbox-1.0.0.jar
```

---

## ⚙️ Configuration

### CDC Configuration

```yaml
outbox:
  cdc:
    enabled: true                # Enable/disable CDC
    polling-interval-ms: 1000    # Poll every 1 second
    batch-size: 100              # Process 100 events per batch
    lock-timeout-ms: 5000        # Lock timeout for queries
```

### Circuit Breaker Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      eventHubPublisher:
        slidingWindowSize: 10              # Monitor last 10 calls
        minimumNumberOfCalls: 5            # Min calls before calculating
        failureRateThreshold: 50           # Open at 50% failure
        waitDurationInOpenState: 10s       # Wait 10s before half-open
```

### Retry Configuration

```yaml
resilience4j:
  retry:
    instances:
      eventHubPublisher:
        maxAttempts: 3                     # Max 3 retry attempts
        waitDuration: 1s                   # Wait 1s between retries
        exponentialBackoffMultiplier: 2    # Double wait each retry
```

### Rate Limiter Configuration

```yaml
resilience4j:
  ratelimiter:
    instances:
      eventHubPublisher:
        limitForPeriod: 100        # 100 calls
        limitRefreshPeriod: 1s     # per second
        timeoutDuration: 500ms     # Wait max 500ms for permit
```

### Backpressure Configuration

```yaml
azure:
  eventhub:
    backpressure:
      max-batch-size: 100          # Max events per batch
      max-wait-time-seconds: 10    # Max wait between batches
      prefetch-count: 300          # Prefetch 300 events
```

---

## 💡 Usage Examples

### Create an Order (with Event Publishing)

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-123",
    "totalAmount": 99.99,
    "currency": "USD",
    "itemCount": 3,
    "shippingAddress": "123 Main St, City, State 12345",
    "userId": "user-456"
  }'
```

**What happens:**
1. OrderController receives request
2. OrderService validates and creates order
3. Order saved to `orders` table
4. OrderCreated event saved to `outbox_events` table (same transaction)
5. CDC polls and publishes event to Event Hub
6. Event consumers process the event

### Monitor Outbox Status

```bash
# Get Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# Key metrics:
# outbox_events_pending - Pending events count
# outbox_events_failed - Failed events count
# outbox_events_published_total - Total published
# outbox_processing_latency_avg - Average latency
```

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

### Circuit Breaker Status

```bash
curl http://localhost:8080/actuator/circuitbreakers
```

---

## 📈 Monitoring & Observability

### Prometheus Metrics

Available at: `http://localhost:8080/actuator/prometheus`

**Key Metrics:**

| Metric | Description |
|--------|-------------|
| `outbox.events.pending` | Current pending events |
| `outbox.events.failed` | Current failed events |
| `outbox.events.dead_letter` | Events in dead letter queue |
| `outbox.events.published` | Total published events |
| `outbox.processing.latency.avg` | Average processing time |
| `resilience4j_circuitbreaker_state` | Circuit breaker state |
| `resilience4j_retry_calls` | Retry attempts |

### Grafana Dashboard

Import dashboard JSON (create from metrics above):

```json
{
  "dashboard": {
    "title": "Outbox Monitoring",
    "panels": [
      {
        "title": "Pending Events",
        "targets": [{"expr": "outbox_events_pending"}]
      },
      {
        "title": "Processing Latency",
        "targets": [{"expr": "outbox_processing_latency_avg"}]
      },
      {
        "title": "Circuit Breaker State",
        "targets": [{"expr": "resilience4j_circuitbreaker_state"}]
      }
    ]
  }
}
```

### Alerting Rules

Example Prometheus alerts:

```yaml
groups:
  - name: outbox_alerts
    rules:
      - alert: HighBacklog
        expr: outbox_events_pending > 10000
        for: 5m
        annotations:
          summary: "High event backlog detected"
          
      - alert: HighFailureRate
        expr: rate(outbox_events_failed_total[5m]) > 10
        for: 5m
        annotations:
          summary: "High event failure rate"
          
      - alert: CircuitBreakerOpen
        expr: resilience4j_circuitbreaker_state{state="open"} == 1
        for: 1m
        annotations:
          summary: "Circuit breaker is open"
```

---

## 🚢 Deployment

### Docker

```dockerfile
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/eventhub-outbox-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and run:

```bash
docker build -t eventhub-outbox:1.0.0 .
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/outboxdb \
  -e EVENTHUB_CONNECTION_STRING=your-connection-string \
  eventhub-outbox:1.0.0
```

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: eventhub-outbox
spec:
  replicas: 3
  selector:
    matchLabels:
      app: eventhub-outbox
  template:
    metadata:
      labels:
        app: eventhub-outbox
    spec:
      containers:
      - name: app
        image: eventhub-outbox:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: db-secret
              key: url
        - name: EVENTHUB_CONNECTION_STRING
          valueFrom:
            secretKeyRef:
              name: eventhub-secret
              key: connection-string
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
```

---

## 📚 Additional Resources

- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- [Azure Event Hub Documentation](https://docs.microsoft.com/azure/event-hubs/)
- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

---

## 🤝 Contributing

Contributions welcome! Please follow these steps:
1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

---

## 📄 License

This project is licensed under the MIT License.

---

## 👥 Authors

- Enterprise Architecture Team

---

## 📧 Support

For questions or issues:
- Create a GitHub issue
- Contact: support@enterprise.com

---

**Built with ❤️ using Spring Boot and Azure Event Hub**
