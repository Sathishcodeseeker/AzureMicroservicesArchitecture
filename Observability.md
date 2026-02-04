# Philosophy of Observability in Spring Boot

## What is Observability?

Observability is the ability to understand the internal state of a system by examining its external outputs. Unlike traditional monitoring, which answers "Is the system working?", observability answers "Why isn't it working?" and "What's happening inside?"

### The Three Pillars of Observability

1. **Metrics** - Numerical measurements over time (CPU usage, request counts, latency)
2. **Logs** - Timestamped records of discrete events
3. **Traces** - The journey of a request through your distributed system

## Core Philosophy

### 1. Observability is Not Monitoring

**Monitoring** tells you when something is broken. **Observability** helps you understand why it's broken and how to fix it.

```
Monitoring: "The API is slow"
Observability: "The API is slow because the database connection pool is exhausted 
               due to a N+1 query problem in the UserService.findAll() method"
```

### 2. Design for Unknown Unknowns

You can't predict every failure mode. Observability should help you debug issues you never anticipated.

**Principle**: Instrument richly, query flexibly.

### 3. Context is Everything

Individual data points are useless without context. Always include:
- Request IDs for correlation
- User/tenant identifiers
- Business context (order ID, transaction type)
- Environment information

### 4. High Cardinality Matters

Modern observability tools can handle high-cardinality data (many unique values). Don't be afraid to include user IDs, trace IDs, or specific error messages.

### 5. Keep Signal-to-Noise Ratio High

More data ≠ better observability. Focus on actionable insights, not vanity metrics.

## Implementing Observability in Spring Boot

### 1. Project Setup

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Spring Boot Actuator for metrics and health checks -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- Micrometer for metrics -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-core</artifactId>
    </dependency>
    
    <!-- Micrometer Tracing (OpenTelemetry) -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    
    <!-- Exporter for your observability backend (e.g., Prometheus) -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    
    <!-- Structured logging -->
    <dependency>
        <groupId>net.logstash.logback</groupId>
        <artifactId>logstash-logback-encoder</artifactId>
        <version>7.4</version>
    </dependency>
</dependencies>
```

### 2. Application Configuration

```yaml
# application.yml
spring:
  application:
    name: my-service

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
  tracing:
    sampling:
      probability: 1.0  # Sample all requests in non-prod
  observations:
    enabled: true

logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
  level:
    root: INFO
    com.yourcompany: DEBUG
```

### 3. Structured Logging with MDC

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    
    public Order createOrder(CreateOrderRequest request) {
        // Add context to MDC (Mapped Diagnostic Context)
        MDC.put("userId", request.getUserId());
        MDC.put("orderType", request.getType());
        
        try {
            log.info("Creating order", kv("itemCount", request.getItems().size()));
            
            Order order = processOrder(request);
            
            log.info("Order created successfully", 
                kv("orderId", order.getId()),
                kv("totalAmount", order.getTotalAmount()));
            
            return order;
            
        } catch (InsufficientInventoryException e) {
            log.warn("Order creation failed due to insufficient inventory",
                kv("requestedItems", request.getItems()),
                kv("error", e.getMessage()));
            throw e;
            
        } catch (Exception e) {
            log.error("Unexpected error creating order", e);
            throw new OrderCreationException("Failed to create order", e);
            
        } finally {
            // Clean up MDC
            MDC.remove("userId");
            MDC.remove("orderType");
        }
    }
    
    // Helper for structured logging
    private static org.slf4j.event.KeyValuePair kv(String key, Object value) {
        return new org.slf4j.event.KeyValuePair(key, value);
    }
}
```

### 4. Custom Metrics

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    
    private final Counter paymentSuccessCounter;
    private final Counter paymentFailureCounter;
    private final Timer paymentProcessingTimer;
    private final MeterRegistry meterRegistry;
    
    public PaymentService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        this.paymentSuccessCounter = Counter.builder("payment.processed")
            .tag("status", "success")
            .description("Number of successful payments")
            .register(meterRegistry);
            
        this.paymentFailureCounter = Counter.builder("payment.processed")
            .tag("status", "failure")
            .description("Number of failed payments")
            .register(meterRegistry);
            
        this.paymentProcessingTimer = Timer.builder("payment.processing.time")
            .description("Time taken to process payment")
            .register(meterRegistry);
    }
    
    public PaymentResult processPayment(PaymentRequest request) {
        return paymentProcessingTimer.record(() -> {
            try {
                PaymentResult result = executePayment(request);
                
                if (result.isSuccess()) {
                    paymentSuccessCounter.increment();
                    
                    // Dynamic tags for high-cardinality data
                    meterRegistry.counter("payment.amount",
                        "currency", request.getCurrency(),
                        "paymentMethod", request.getMethod())
                        .increment(request.getAmount());
                } else {
                    paymentFailureCounter.increment();
                    
                    meterRegistry.counter("payment.failure.reason",
                        "reason", result.getFailureReason())
                        .increment();
                }
                
                return result;
                
            } catch (Exception e) {
                paymentFailureCounter.increment();
                throw e;
            }
        });
    }
}
```

### 5. Custom Metrics with Gauges

```java
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CacheMonitor {
    
    private final Cache cache;
    
    public CacheMonitor(MeterRegistry registry, Cache cache) {
        this.cache = cache;
        
        // Monitor cache size
        Gauge.builder("cache.size", cache, Cache::size)
            .description("Current cache size")
            .register(registry);
            
        // Monitor cache hit rate
        Gauge.builder("cache.hit.rate", cache, c -> c.getHitRate())
            .description("Cache hit rate")
            .register(registry);
    }
}
```

### 6. Distributed Tracing

```java
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    
    private final ObservationRegistry observationRegistry;
    private final UserRepository userRepository;
    private final EmailService emailService;
    
    public UserService(ObservationRegistry observationRegistry,
                       UserRepository userRepository,
                       EmailService emailService) {
        this.observationRegistry = observationRegistry;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }
    
    public User registerUser(UserRegistrationRequest request) {
        // Create an observation for this business operation
        return Observation.createNotStarted("user.registration", observationRegistry)
            .lowCardinalityKeyValue("userType", request.getType())
            .highCardinalityKeyValue("email", request.getEmail())
            .observe(() -> {
                // This entire block will be traced
                User user = createUser(request);
                sendWelcomeEmail(user);
                return user;
            });
    }
    
    private User createUser(UserRegistrationRequest request) {
        // Child span automatically created
        return Observation.createNotStarted("user.create", observationRegistry)
            .observe(() -> {
                User user = new User(request);
                return userRepository.save(user);
            });
    }
    
    private void sendWelcomeEmail(User user) {
        // Another child span
        Observation.createNotStarted("email.send", observationRegistry)
            .lowCardinalityKeyValue("emailType", "welcome")
            .observe(() -> emailService.sendWelcome(user));
    }
}
```

### 7. Request/Response Logging Filter

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
public class ObservabilityFilter extends OncePerRequestFilter {
    
    private static final Logger log = LoggerFactory.getLogger(ObservabilityFilter.class);
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) 
            throws ServletException, IOException {
        
        long startTime = System.currentTimeMillis();
        String requestId = UUID.randomUUID().toString();
        
        // Add trace context to MDC
        MDC.put("requestId", requestId);
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());
        
        // Add to response header for client correlation
        response.setHeader("X-Request-ID", requestId);
        
        try {
            log.info("Incoming request");
            
            filterChain.doFilter(request, response);
            
            long duration = System.currentTimeMillis() - startTime;
            
            log.info("Request completed",
                kv("status", response.getStatus()),
                kv("durationMs", duration));
                
        } catch (Exception e) {
            log.error("Request failed", e);
            throw e;
            
        } finally {
            MDC.clear();
        }
    }
}
```

### 8. Exception Tracking

```java
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private final MeterRegistry meterRegistry;
    
    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        meterRegistry.counter("exception.business",
            "type", e.getClass().getSimpleName(),
            "code", e.getErrorCode()).increment();
            
        log.warn("Business exception occurred",
            kv("errorCode", e.getErrorCode()),
            kv("message", e.getMessage()));
            
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(new ErrorResponse(e));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception e) {
        meterRegistry.counter("exception.unexpected",
            "type", e.getClass().getSimpleName()).increment();
            
        log.error("Unexpected exception occurred", e);
        
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("An unexpected error occurred"));
    }
}
```

### 9. Health Checks

```java
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class CustomHealthIndicator implements HealthIndicator {
    
    private final PaymentGateway paymentGateway;
    
    public CustomHealthIndicator(PaymentGateway paymentGateway) {
        this.paymentGateway = paymentGateway;
    }
    
    @Override
    public Health health() {
        try {
            boolean isAvailable = paymentGateway.ping();
            
            if (isAvailable) {
                return Health.up()
                    .withDetail("paymentGateway", "available")
                    .withDetail("lastCheck", System.currentTimeMillis())
                    .build();
            } else {
                return Health.down()
                    .withDetail("paymentGateway", "unavailable")
                    .build();
            }
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

### 10. Business Metrics

```java
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class OrderMetricsService {
    
    private final MeterRegistry registry;
    
    public OrderMetricsService(MeterRegistry registry) {
        this.registry = registry;
    }
    
    @Timed(value = "order.processing", description = "Time to process an order")
    public void recordOrderPlaced(Order order) {
        // Counter for total orders
        registry.counter("orders.placed",
            "status", order.getStatus(),
            "channel", order.getChannel()).increment();
        
        // Distribution summary for order values
        registry.summary("order.value",
            "currency", order.getCurrency())
            .record(order.getTotalAmount());
        
        // Track items per order
        registry.summary("order.items.count")
            .record(order.getItems().size());
    }
    
    public void recordOrderFulfilled(Order order, long fulfillmentTimeMs) {
        registry.timer("order.fulfillment.time",
            "priority", order.getPriority())
            .record(fulfillmentTimeMs, TimeUnit.MILLISECONDS);
    }
}
```

## Best Practices

### 1. Use Semantic Naming

```java
// Bad
counter.increment();
timer.record(duration);

// Good
registry.counter("user.login.attempts", "result", "success").increment();
registry.timer("database.query.duration", "table", "users", "operation", "select")
    .record(duration);
```

### 2. Include Business Context

```java
// Technical metric only
log.info("Request processed");

// With business context
log.info("Order processed",
    kv("orderId", order.getId()),
    kv("customerId", order.getCustomerId()),
    kv("itemCount", order.getItems().size()),
    kv("revenue", order.getTotalAmount()));
```

### 3. Log Levels Matter

- **ERROR**: Something failed, needs immediate attention
- **WARN**: Something unexpected, but recoverable
- **INFO**: Significant business events
- **DEBUG**: Detailed flow information for troubleshooting
- **TRACE**: Very detailed, usually method entry/exit

### 4. Avoid Logging Sensitive Data

```java
// Bad
log.info("User logged in: " + user);  // May contain password, SSN, etc.

// Good
log.info("User logged in",
    kv("userId", user.getId()),
    kv("email", maskEmail(user.getEmail())));
```

### 5. Use Sampling for High-Volume Operations

```java
// Don't log every cache hit
if (random.nextDouble() < 0.01) {  // 1% sampling
    log.debug("Cache hit", kv("key", key));
}

// Always log cache misses
if (!cacheHit) {
    log.info("Cache miss", kv("key", key));
}
```

## Common Patterns

### Request Correlation

```java
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) {
        String correlationId = request.getHeader("X-Correlation-ID");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Correlation-ID", correlationId);
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

### SLA Tracking

```java
@Aspect
@Component
public class SLAMonitoringAspect {
    
    private final MeterRegistry registry;
    
    @Around("@annotation(sla)")
    public Object monitor(ProceedingJoinPoint pjp, SLA sla) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        String operation = pjp.getSignature().getName();
        
        try {
            Object result = pjp.proceed();
            
            sample.stop(registry.timer("sla.operations",
                "operation", operation,
                "status", "success"));
                
            return result;
            
        } catch (Exception e) {
            sample.stop(registry.timer("sla.operations",
                "operation", operation,
                "status", "failure"));
            throw e;
        }
    }
}

// Usage
@SLA(threshold = 100, unit = TimeUnit.MILLISECONDS)
public User getUser(Long userId) {
    return userRepository.findById(userId);
}
```

## Testing Observability

```java
@SpringBootTest
class ObservabilityTest {
    
    @Autowired
    private MeterRegistry meterRegistry;
    
    @Test
    void shouldRecordMetricsForSuccessfulOrder() {
        // Given
        Counter counter = meterRegistry.find("orders.placed")
            .tag("status", "success")
            .counter();
        double initialCount = counter != null ? counter.count() : 0;
        
        // When
        orderService.createOrder(validRequest);
        
        // Then
        counter = meterRegistry.find("orders.placed")
            .tag("status", "success")
            .counter();
        assertThat(counter.count()).isEqualTo(initialCount + 1);
    }
}
```

## Conclusion

Observability is about understanding your system deeply:

1. **Start with context**: Every log, metric, and trace should tell a story
2. **Think in cardinality**: Don't be afraid of detailed tags
3. **Measure what matters**: Focus on business outcomes, not just technical metrics
4. **Make it actionable**: Data without insight is just noise
5. **Iterate**: Observability improves as you learn what questions you need to answer

The goal is to be able to ask any question about your system and have the data to answer it.
