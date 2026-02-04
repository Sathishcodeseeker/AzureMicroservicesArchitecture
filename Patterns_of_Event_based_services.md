# Design Patterns for Event-Based Microservices

A comprehensive guide to essential patterns for building robust, scalable event-driven microservices architectures.

---

## Table of Contents

1. [Critical Patterns (Must Know)](#critical-patterns-must-know)
2. [Important Patterns (Should Know)](#important-patterns-should-know)
3. [Microservices-Specific Patterns](#microservices-specific-patterns)
4. [Event-Driven Architecture Patterns](#event-driven-architecture-patterns)
5. [Data Management Patterns](#data-management-patterns)
6. [Resilience Patterns](#resilience-patterns)
7. [Real-World Implementation Examples](#real-world-implementation-examples)
8. [Pattern Combinations](#pattern-combinations)

---

## Critical Patterns (Must Know)

These patterns are absolutely essential for event-based microservices.

### 1. Observer Pattern ⭐⭐⭐⭐⭐

**Why Critical**: The foundation of event-driven architecture. Microservices observe and react to events.

**Use Cases**:
- Service-to-service communication via events
- Real-time notifications
- Event streaming (Kafka, RabbitMQ, AWS SNS/SQS)

#### Traditional Observer
```java
// Event
class OrderCreatedEvent {
    private String orderId;
    private double amount;
    private LocalDateTime timestamp;
    
    // Constructor, getters
}

// Subject (Event Publisher)
interface EventPublisher {
    void subscribe(EventSubscriber subscriber);
    void unsubscribe(EventSubscriber subscriber);
    void notifySubscribers(Event event);
}

// Observer (Event Subscriber)
interface EventSubscriber {
    void handleEvent(Event event);
}

// Concrete implementations
class OrderService implements EventPublisher {
    private List<EventSubscriber> subscribers = new ArrayList<>();
    
    public void createOrder(Order order) {
        // Create order logic
        
        // Publish event
        OrderCreatedEvent event = new OrderCreatedEvent(order.getId(), order.getAmount());
        notifySubscribers(event);
    }
    
    public void subscribe(EventSubscriber subscriber) {
        subscribers.add(subscriber);
    }
    
    public void notifySubscribers(Event event) {
        subscribers.forEach(sub -> sub.handleEvent(event));
    }
}

class InventoryService implements EventSubscriber {
    public void handleEvent(Event event) {
        if (event instanceof OrderCreatedEvent) {
            OrderCreatedEvent orderEvent = (OrderCreatedEvent) event;
            reserveInventory(orderEvent.getOrderId());
        }
    }
    
    private void reserveInventory(String orderId) {
        System.out.println("Reserving inventory for order: " + orderId);
    }
}

class NotificationService implements EventSubscriber {
    public void handleEvent(Event event) {
        if (event instanceof OrderCreatedEvent) {
            OrderCreatedEvent orderEvent = (OrderCreatedEvent) event;
            sendConfirmationEmail(orderEvent);
        }
    }
    
    private void sendConfirmationEmail(OrderCreatedEvent event) {
        System.out.println("Sending confirmation email for order: " + event.getOrderId());
    }
}
```

#### Spring Boot Event-Driven Example
```java
// Event
public class OrderCreatedEvent extends ApplicationEvent {
    private String orderId;
    private double amount;
    
    public OrderCreatedEvent(Object source, String orderId, double amount) {
        super(source);
        this.orderId = orderId;
        this.amount = amount;
    }
    
    // Getters
}

// Publisher
@Service
public class OrderService {
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    public void createOrder(Order order) {
        // Save order to database
        orderRepository.save(order);
        
        // Publish event
        OrderCreatedEvent event = new OrderCreatedEvent(
            this, 
            order.getId(), 
            order.getAmount()
        );
        eventPublisher.publishEvent(event);
    }
}

// Subscribers
@Component
public class InventoryService {
    @EventListener
    @Async
    public void handleOrderCreated(OrderCreatedEvent event) {
        // Reserve inventory
        System.out.println("Reserving inventory for: " + event.getOrderId());
    }
}

@Component
public class NotificationService {
    @EventListener
    @Async
    public void handleOrderCreated(OrderCreatedEvent event) {
        // Send notification
        System.out.println("Sending email for: " + event.getOrderId());
    }
}

@Component
public class PaymentService {
    @EventListener
    @Async
    public void handleOrderCreated(OrderCreatedEvent event) {
        // Process payment
        System.out.println("Processing payment for: " + event.getOrderId());
    }
}
```

#### Kafka-Based Event-Driven Architecture
```java
// Event DTO
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderEvent {
    private String eventId;
    private String eventType;
    private String orderId;
    private double amount;
    private String customerId;
    private LocalDateTime timestamp;
}

// Producer (Publisher)
@Service
public class OrderEventProducer {
    @Autowired
    private KafkaTemplate<String, OrderEvent> kafkaTemplate;
    
    private static final String TOPIC = "order-events";
    
    public void publishOrderCreated(Order order) {
        OrderEvent event = new OrderEvent(
            UUID.randomUUID().toString(),
            "ORDER_CREATED",
            order.getId(),
            order.getAmount(),
            order.getCustomerId(),
            LocalDateTime.now()
        );
        
        kafkaTemplate.send(TOPIC, event.getOrderId(), event);
        System.out.println("Published: " + event);
    }
}

// Consumer (Subscriber) - Inventory Service
@Service
public class InventoryEventConsumer {
    @KafkaListener(topics = "order-events", groupId = "inventory-service")
    public void consumeOrderEvent(OrderEvent event) {
        if ("ORDER_CREATED".equals(event.getEventType())) {
            handleOrderCreated(event);
        }
    }
    
    private void handleOrderCreated(OrderEvent event) {
        // Reserve inventory
        System.out.println("Inventory Service: Reserving items for order " + event.getOrderId());
        
        // Could publish InventoryReservedEvent
    }
}

// Consumer (Subscriber) - Notification Service
@Service
public class NotificationEventConsumer {
    @KafkaListener(topics = "order-events", groupId = "notification-service")
    public void consumeOrderEvent(OrderEvent event) {
        if ("ORDER_CREATED".equals(event.getEventType())) {
            sendOrderConfirmation(event);
        }
    }
    
    private void sendOrderConfirmation(OrderEvent event) {
        System.out.println("Notification Service: Sending email for order " + event.getOrderId());
    }
}

// Consumer (Subscriber) - Payment Service
@Service
public class PaymentEventConsumer {
    @KafkaListener(topics = "order-events", groupId = "payment-service")
    public void consumeOrderEvent(OrderEvent event) {
        if ("ORDER_CREATED".equals(event.getEventType())) {
            processPayment(event);
        }
    }
    
    private void processPayment(OrderEvent event) {
        System.out.println("Payment Service: Processing payment for order " + event.getOrderId());
        
        // Could publish PaymentProcessedEvent
    }
}
```

**Key Points**:
- Loose coupling between services
- Asynchronous communication
- Each service can scale independently
- Message brokers (Kafka, RabbitMQ) act as event bus

---

### 2. Command Pattern ⭐⭐⭐⭐⭐

**Why Critical**: Commands encapsulate requests as objects, essential for CQRS, event sourcing, and async processing.

**Use Cases**:
- CQRS (Command Query Responsibility Segregation)
- Undo/redo operations
- Request queuing and logging
- Transaction management

```java
// Command interface
public interface Command {
    void execute();
    void undo();
}

// Concrete Command
public class CreateOrderCommand implements Command {
    private String orderId;
    private String customerId;
    private List<OrderItem> items;
    private OrderService orderService;
    
    public CreateOrderCommand(String orderId, String customerId, 
                              List<OrderItem> items, OrderService orderService) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.items = items;
        this.orderService = orderService;
    }
    
    @Override
    public void execute() {
        orderService.createOrder(orderId, customerId, items);
    }
    
    @Override
    public void undo() {
        orderService.cancelOrder(orderId);
    }
}

public class UpdateInventoryCommand implements Command {
    private String productId;
    private int quantity;
    private InventoryService inventoryService;
    
    public UpdateInventoryCommand(String productId, int quantity, 
                                   InventoryService inventoryService) {
        this.productId = productId;
        this.quantity = quantity;
        this.inventoryService = inventoryService;
    }
    
    @Override
    public void execute() {
        inventoryService.updateStock(productId, quantity);
    }
    
    @Override
    public void undo() {
        inventoryService.updateStock(productId, -quantity);
    }
}

// Command Handler (Invoker)
@Service
public class CommandHandler {
    private Queue<Command> commandQueue = new LinkedList<>();
    private Stack<Command> executedCommands = new Stack<>();
    
    public void submitCommand(Command command) {
        commandQueue.add(command);
    }
    
    public void executeCommands() {
        while (!commandQueue.isEmpty()) {
            Command command = commandQueue.poll();
            command.execute();
            executedCommands.push(command);
        }
    }
    
    public void undoLastCommand() {
        if (!executedCommands.isEmpty()) {
            Command command = executedCommands.pop();
            command.undo();
        }
    }
}

// CQRS with Commands
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @Autowired
    private CommandHandler commandHandler;
    
    @Autowired
    private OrderService orderService;
    
    // Command side (Write)
    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody CreateOrderRequest request) {
        String orderId = UUID.randomUUID().toString();
        
        Command command = new CreateOrderCommand(
            orderId,
            request.getCustomerId(),
            request.getItems(),
            orderService
        );
        
        commandHandler.submitCommand(command);
        commandHandler.executeCommands();
        
        return ResponseEntity.ok(orderId);
    }
    
    // Query side (Read) - separate from commands
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        // Query from read model (potentially different database)
        Order order = orderService.findById(orderId);
        return ResponseEntity.ok(order);
    }
}
```

**Event Sourcing with Commands**:
```java
// Event Store
public class EventStore {
    private List<Event> events = new ArrayList<>();
    
    public void saveEvent(Event event) {
        events.add(event);
    }
    
    public List<Event> getEventsForAggregate(String aggregateId) {
        return events.stream()
            .filter(e -> e.getAggregateId().equals(aggregateId))
            .collect(Collectors.toList());
    }
}

// Command with Event Sourcing
public class OrderAggregate {
    private String orderId;
    private String customerId;
    private OrderStatus status;
    private List<Event> uncommittedEvents = new ArrayList<>();
    
    // Apply command
    public void createOrder(String customerId, List<OrderItem> items) {
        // Validate
        if (customerId == null) {
            throw new IllegalArgumentException("Customer required");
        }
        
        // Create event
        OrderCreatedEvent event = new OrderCreatedEvent(
            UUID.randomUUID().toString(),
            customerId,
            items,
            LocalDateTime.now()
        );
        
        // Apply event
        apply(event);
    }
    
    // Event handler
    private void apply(OrderCreatedEvent event) {
        this.orderId = event.getOrderId();
        this.customerId = event.getCustomerId();
        this.status = OrderStatus.CREATED;
        uncommittedEvents.add(event);
    }
    
    // Rebuild state from events
    public static OrderAggregate fromEvents(List<Event> events) {
        OrderAggregate aggregate = new OrderAggregate();
        events.forEach(aggregate::applyEvent);
        return aggregate;
    }
    
    private void applyEvent(Event event) {
        if (event instanceof OrderCreatedEvent) {
            apply((OrderCreatedEvent) event);
        }
        // Handle other event types
    }
    
    public List<Event> getUncommittedEvents() {
        return uncommittedEvents;
    }
}
```

---

### 3. Saga Pattern ⭐⭐⭐⭐⭐

**Why Critical**: Manages distributed transactions across microservices without 2PC (Two-Phase Commit).

**Use Cases**:
- Multi-service transactions
- Compensating actions on failure
- Long-running business processes

#### Choreography-Based Saga
```java
// Each service listens and publishes events autonomously

// Order Service
@Service
public class OrderSagaService {
    @Autowired
    private EventPublisher eventPublisher;
    
    @Autowired
    private OrderRepository orderRepository;
    
    // Step 1: Start saga
    public void createOrder(Order order) {
        order.setStatus(OrderStatus.PENDING);
        orderRepository.save(order);
        
        // Publish event
        eventPublisher.publish(new OrderCreatedEvent(order.getId(), order.getAmount()));
    }
    
    // Step 4: Complete or compensate
    @EventListener
    public void handlePaymentProcessed(PaymentProcessedEvent event) {
        Order order = orderRepository.findById(event.getOrderId());
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        
        eventPublisher.publish(new OrderConfirmedEvent(order.getId()));
    }
    
    @EventListener
    public void handlePaymentFailed(PaymentFailedEvent event) {
        // Compensating action
        Order order = orderRepository.findById(event.getOrderId());
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        
        eventPublisher.publish(new OrderCancelledEvent(order.getId()));
    }
}

// Inventory Service
@Service
public class InventorySagaService {
    @Autowired
    private EventPublisher eventPublisher;
    
    // Step 2: Reserve inventory
    @EventListener
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            reserveInventory(event.getOrderId());
            eventPublisher.publish(new InventoryReservedEvent(event.getOrderId()));
        } catch (Exception e) {
            eventPublisher.publish(new InventoryReservationFailedEvent(event.getOrderId()));
        }
    }
    
    // Compensating action
    @EventListener
    public void handleOrderCancelled(OrderCancelledEvent event) {
        releaseInventory(event.getOrderId());
    }
}

// Payment Service
@Service
public class PaymentSagaService {
    @Autowired
    private EventPublisher eventPublisher;
    
    // Step 3: Process payment
    @EventListener
    public void handleInventoryReserved(InventoryReservedEvent event) {
        try {
            processPayment(event.getOrderId());
            eventPublisher.publish(new PaymentProcessedEvent(event.getOrderId()));
        } catch (Exception e) {
            eventPublisher.publish(new PaymentFailedEvent(event.getOrderId()));
        }
    }
    
    // Compensating action
    @EventListener
    public void handleOrderCancelled(OrderCancelledEvent event) {
        refundPayment(event.getOrderId());
    }
}
```

#### Orchestration-Based Saga
```java
// Central orchestrator coordinates the saga

// Saga Orchestrator
@Service
public class OrderSagaOrchestrator {
    @Autowired
    private InventoryService inventoryService;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private OrderService orderService;
    
    public void executeOrderSaga(Order order) {
        try {
            // Step 1: Create order
            orderService.createOrder(order);
            
            // Step 2: Reserve inventory
            inventoryService.reserveInventory(order.getId(), order.getItems());
            
            // Step 3: Process payment
            paymentService.processPayment(order.getId(), order.getAmount());
            
            // Step 4: Confirm order
            orderService.confirmOrder(order.getId());
            
        } catch (InventoryException e) {
            // Compensate: cancel order
            orderService.cancelOrder(order.getId());
            throw new SagaException("Inventory reservation failed", e);
            
        } catch (PaymentException e) {
            // Compensate: release inventory and cancel order
            inventoryService.releaseInventory(order.getId());
            orderService.cancelOrder(order.getId());
            throw new SagaException("Payment failed", e);
        }
    }
}

// With State Machine
@Service
public class StatefulOrderSagaOrchestrator {
    enum SagaState {
        STARTED,
        INVENTORY_RESERVED,
        PAYMENT_PROCESSED,
        COMPLETED,
        COMPENSATING,
        FAILED
    }
    
    private SagaState currentState = SagaState.STARTED;
    
    public void execute(Order order) {
        try {
            // State: STARTED -> Reserve Inventory
            inventoryService.reserveInventory(order.getId());
            currentState = SagaState.INVENTORY_RESERVED;
            
            // State: INVENTORY_RESERVED -> Process Payment
            paymentService.processPayment(order.getId());
            currentState = SagaState.PAYMENT_PROCESSED;
            
            // State: PAYMENT_PROCESSED -> Complete
            orderService.confirmOrder(order.getId());
            currentState = SagaState.COMPLETED;
            
        } catch (Exception e) {
            currentState = SagaState.COMPENSATING;
            compensate(order.getId());
        }
    }
    
    private void compensate(String orderId) {
        switch (currentState) {
            case PAYMENT_PROCESSED:
                paymentService.refund(orderId);
                // Fall through
            case INVENTORY_RESERVED:
                inventoryService.releaseInventory(orderId);
                // Fall through
            case STARTED:
                orderService.cancelOrder(orderId);
                break;
        }
        currentState = SagaState.FAILED;
    }
}
```

**Choreography vs Orchestration**:

| Aspect | Choreography | Orchestration |
|--------|--------------|---------------|
| **Coordination** | Decentralized | Centralized |
| **Complexity** | Harder to track flow | Easier to understand |
| **Coupling** | Loose | Tighter |
| **Best For** | Simple sagas | Complex workflows |

---

### 4. Strategy Pattern ⭐⭐⭐⭐

**Why Important**: Different message processing strategies, routing logic, retry strategies.

**Use Cases**:
- Message routing
- Retry policies
- Serialization strategies
- Load balancing algorithms

```java
// Message Processing Strategy
public interface MessageProcessingStrategy {
    void process(Message message);
}

@Component
public class SyncMessageProcessor implements MessageProcessingStrategy {
    public void process(Message message) {
        // Process immediately
        System.out.println("Processing synchronously: " + message);
    }
}

@Component
public class AsyncMessageProcessor implements MessageProcessingStrategy {
    @Async
    public void process(Message message) {
        // Process asynchronously
        System.out.println("Processing asynchronously: " + message);
    }
}

@Component
public class BatchMessageProcessor implements MessageProcessingStrategy {
    private Queue<Message> batch = new LinkedList<>();
    
    public void process(Message message) {
        batch.add(message);
        if (batch.size() >= 100) {
            processBatch();
        }
    }
    
    private void processBatch() {
        System.out.println("Processing batch of " + batch.size());
        batch.clear();
    }
}

// Message Handler with Strategy
@Service
public class MessageHandler {
    private MessageProcessingStrategy strategy;
    
    public void setStrategy(MessageProcessingStrategy strategy) {
        this.strategy = strategy;
    }
    
    public void handleMessage(Message message) {
        if (message.getPriority() == Priority.HIGH) {
            setStrategy(new SyncMessageProcessor());
        } else if (message.getSize() > 1000) {
            setStrategy(new BatchMessageProcessor());
        } else {
            setStrategy(new AsyncMessageProcessor());
        }
        
        strategy.process(message);
    }
}

// Retry Strategy
public interface RetryStrategy {
    boolean shouldRetry(int attemptNumber);
    long getDelayMillis(int attemptNumber);
}

@Component
public class ExponentialBackoffRetry implements RetryStrategy {
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_DELAY = 1000;
    
    public boolean shouldRetry(int attemptNumber) {
        return attemptNumber < MAX_ATTEMPTS;
    }
    
    public long getDelayMillis(int attemptNumber) {
        return BASE_DELAY * (long) Math.pow(2, attemptNumber);
    }
}

@Component
public class FixedDelayRetry implements RetryStrategy {
    private static final int MAX_ATTEMPTS = 3;
    private static final long DELAY = 5000;
    
    public boolean shouldRetry(int attemptNumber) {
        return attemptNumber < MAX_ATTEMPTS;
    }
    
    public long getDelayMillis(int attemptNumber) {
        return DELAY;
    }
}

// Message Sender with Retry
@Service
public class ResilientMessageSender {
    private RetryStrategy retryStrategy;
    
    public void sendWithRetry(Message message) {
        int attempt = 0;
        
        while (true) {
            try {
                send(message);
                return; // Success
            } catch (Exception e) {
                attempt++;
                if (!retryStrategy.shouldRetry(attempt)) {
                    throw new MessageSendException("Failed after " + attempt + " attempts", e);
                }
                
                long delay = retryStrategy.getDelayMillis(attempt);
                sleep(delay);
            }
        }
    }
}
```

---

### 5. Adapter Pattern ⭐⭐⭐⭐

**Why Important**: Integrate with different message formats, protocols, and external systems.

**Use Cases**:
- Protocol adapters (REST, gRPC, SOAP)
- Message format converters
- Legacy system integration
- Third-party API integration

```java
// Target interface (what our system expects)
public interface EventPublisher {
    void publishEvent(Event event);
}

// Adaptee (Kafka - different interface)
public class KafkaProducer {
    public void send(String topic, String key, String value) {
        System.out.println("Kafka: Sending to " + topic);
    }
}

// Adapter
@Service
public class KafkaEventPublisherAdapter implements EventPublisher {
    @Autowired
    private KafkaProducer kafkaProducer;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Override
    public void publishEvent(Event event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaProducer.send("events-topic", event.getId(), json);
        } catch (Exception e) {
            throw new PublishException("Failed to publish event", e);
        }
    }
}

// Another Adaptee (RabbitMQ)
public class RabbitMQSender {
    public void publish(String exchange, String routingKey, byte[] message) {
        System.out.println("RabbitMQ: Publishing to " + exchange);
    }
}

// Another Adapter
@Service
public class RabbitMQEventPublisherAdapter implements EventPublisher {
    @Autowired
    private RabbitMQSender rabbitMQSender;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Override
    public void publishEvent(Event event) {
        try {
            byte[] message = objectMapper.writeValueAsBytes(event);
            rabbitMQSender.publish("events-exchange", event.getType(), message);
        } catch (Exception e) {
            throw new PublishException("Failed to publish event", e);
        }
    }
}

// Usage - can switch adapters via configuration
@Service
public class OrderService {
    @Autowired
    @Qualifier("kafkaAdapter")  // or "rabbitMQAdapter"
    private EventPublisher eventPublisher;
    
    public void createOrder(Order order) {
        // Business logic
        
        // Publish event (adapter handles the details)
        eventPublisher.publishEvent(new OrderCreatedEvent(order));
    }
}

// Message Format Adapter
public interface MessageSerializer {
    byte[] serialize(Object obj);
    Object deserialize(byte[] data, Class<?> clazz);
}

@Component
public class JsonMessageSerializer implements MessageSerializer {
    @Autowired
    private ObjectMapper objectMapper;
    
    public byte[] serialize(Object obj) {
        return objectMapper.writeValueAsBytes(obj);
    }
    
    public Object deserialize(byte[] data, Class<?> clazz) {
        return objectMapper.readValue(data, clazz);
    }
}

@Component
public class ProtobufMessageSerializer implements MessageSerializer {
    public byte[] serialize(Object obj) {
        // Protobuf serialization
        return ((Message) obj).toByteArray();
    }
    
    public Object deserialize(byte[] data, Class<?> clazz) {
        // Protobuf deserialization
        return parseFrom(data);
    }
}

@Component
public class AvroMessageSerializer implements MessageSerializer {
    public byte[] serialize(Object obj) {
        // Avro serialization
        return avroSerialize(obj);
    }
    
    public Object deserialize(byte[] data, Class<?> clazz) {
        // Avro deserialization
        return avroDeserialize(data, clazz);
    }
}
```

---

## Important Patterns (Should Know)

### 6. Chain of Responsibility ⭐⭐⭐⭐

**Why Important**: Message filtering, validation chains, middleware pipelines.

```java
// Request handler chain for message processing
public abstract class MessageHandler {
    protected MessageHandler nextHandler;
    
    public void setNext(MessageHandler handler) {
        this.nextHandler = handler;
    }
    
    public void handle(Message message) {
        if (canHandle(message)) {
            process(message);
        }
        
        if (nextHandler != null) {
            nextHandler.handle(message);
        }
    }
    
    protected abstract boolean canHandle(Message message);
    protected abstract void process(Message message);
}

// Validation handler
public class ValidationHandler extends MessageHandler {
    protected boolean canHandle(Message message) {
        return true; // Always validate
    }
    
    protected void process(Message message) {
        if (message.getPayload() == null) {
            throw new ValidationException("Payload cannot be null");
        }
        System.out.println("Validation passed");
    }
}

// Authentication handler
public class AuthenticationHandler extends MessageHandler {
    protected boolean canHandle(Message message) {
        return message.requiresAuth();
    }
    
    protected void process(Message message) {
        if (!isAuthenticated(message)) {
            throw new AuthenticationException("Invalid credentials");
        }
        System.out.println("Authentication passed");
    }
}

// Logging handler
public class LoggingHandler extends MessageHandler {
    protected boolean canHandle(Message message) {
        return true; // Always log
    }
    
    protected void process(Message message) {
        System.out.println("Processing message: " + message.getId());
    }
}

// Business logic handler
public class BusinessLogicHandler extends MessageHandler {
    protected boolean canHandle(Message message) {
        return true;
    }
    
    protected void process(Message message) {
        // Actual business logic
        System.out.println("Executing business logic");
    }
}

// Setup chain
@Configuration
public class HandlerChainConfig {
    @Bean
    public MessageHandler handlerChain() {
        MessageHandler validation = new ValidationHandler();
        MessageHandler authentication = new AuthenticationHandler();
        MessageHandler logging = new LoggingHandler();
        MessageHandler business = new BusinessLogicHandler();
        
        validation.setNext(authentication);
        authentication.setNext(logging);
        logging.setNext(business);
        
        return validation;
    }
}

// Usage
@Service
public class MessageProcessor {
    @Autowired
    private MessageHandler handlerChain;
    
    public void processMessage(Message message) {
        handlerChain.handle(message);
    }
}
```

---

### 7. Factory Pattern ⭐⭐⭐

**Why Important**: Create different types of events, messages, or handlers based on context.

```java
// Event Factory
public interface Event {
    String getType();
    String getAggregateId();
}

public class OrderCreatedEvent implements Event {
    private String orderId;
    // fields, constructor, getters
    
    public String getType() { return "ORDER_CREATED"; }
    public String getAggregateId() { return orderId; }
}

public class PaymentProcessedEvent implements Event {
    private String paymentId;
    // fields, constructor, getters
    
    public String getType() { return "PAYMENT_PROCESSED"; }
    public String getAggregateId() { return paymentId; }
}

// Factory
public class EventFactory {
    public static Event createEvent(String eventType, Map<String, Object> data) {
        switch (eventType) {
            case "ORDER_CREATED":
                return new OrderCreatedEvent(
                    (String) data.get("orderId"),
                    (Double) data.get("amount")
                );
            case "PAYMENT_PROCESSED":
                return new PaymentProcessedEvent(
                    (String) data.get("paymentId"),
                    (String) data.get("orderId")
                );
            default:
                throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
    }
}

// Message Handler Factory
public interface MessageHandler {
    void handle(Message message);
}

public class OrderMessageHandler implements MessageHandler {
    public void handle(Message message) {
        System.out.println("Handling order message");
    }
}

public class PaymentMessageHandler implements MessageHandler {
    public void handle(Message message) {
        System.out.println("Handling payment message");
    }
}

@Component
public class MessageHandlerFactory {
    @Autowired
    private ApplicationContext context;
    
    public MessageHandler getHandler(String messageType) {
        switch (messageType) {
            case "ORDER":
                return context.getBean(OrderMessageHandler.class);
            case "PAYMENT":
                return context.getBean(PaymentMessageHandler.class);
            default:
                return new DefaultMessageHandler();
        }
    }
}
```

---

### 8. Proxy Pattern ⭐⭐⭐

**Why Important**: Add cross-cutting concerns like caching, logging, circuit breaking without modifying service code.

```java
// Service interface
public interface OrderService {
    Order getOrder(String orderId);
    void createOrder(Order order);
}

// Real service
@Service
public class OrderServiceImpl implements OrderService {
    public Order getOrder(String orderId) {
        // Expensive database call
        return orderRepository.findById(orderId);
    }
    
    public void createOrder(Order order) {
        orderRepository.save(order);
    }
}

// Caching Proxy
@Service
public class CachingOrderServiceProxy implements OrderService {
    @Autowired
    private OrderService realService;
    
    private Map<String, Order> cache = new ConcurrentHashMap<>();
    
    public Order getOrder(String orderId) {
        if (cache.containsKey(orderId)) {
            System.out.println("Cache hit for order: " + orderId);
            return cache.get(orderId);
        }
        
        Order order = realService.getOrder(orderId);
        cache.put(orderId, order);
        return order;
    }
    
    public void createOrder(Order order) {
        realService.createOrder(order);
        cache.put(order.getId(), order);
    }
}

// Circuit Breaker Proxy
@Service
public class CircuitBreakerOrderServiceProxy implements OrderService {
    @Autowired
    private OrderService realService;
    
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    
    public Order getOrder(String orderId) {
        if (circuitBreaker.isOpen()) {
            throw new ServiceUnavailableException("Circuit breaker is open");
        }
        
        try {
            Order order = realService.getOrder(orderId);
            circuitBreaker.recordSuccess();
            return order;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }
    
    public void createOrder(Order order) {
        if (circuitBreaker.isOpen()) {
            throw new ServiceUnavailableException("Circuit breaker is open");
        }
        
        try {
            realService.createOrder(order);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }
}
```

---

## Microservices-Specific Patterns

### 9. Circuit Breaker Pattern ⭐⭐⭐⭐⭐

**Why Critical**: Prevent cascading failures in distributed systems.

```java
// Using Resilience4j
@Service
public class OrderService {
    @Autowired
    private PaymentServiceClient paymentClient;
    
    @CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentService")
    @RateLimiter(name = "paymentService")
    public Payment processPayment(String orderId, double amount) {
        return paymentClient.processPayment(orderId, amount);
    }
    
    // Fallback method
    private Payment paymentFallback(String orderId, double amount, Exception e) {
        log.error("Payment service unavailable, using fallback", e);
        return Payment.pending(orderId, amount);
    }
}

// Configuration
// application.yml
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        registerHealthIndicator: true
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        permittedNumberOfCallsInHalfOpenState: 3
        waitDurationInOpenState: 30s
        failureRateThreshold: 50
        
  retry:
    instances:
      paymentService:
        maxAttempts: 3
        waitDuration: 1s
        
  ratelimiter:
    instances:
      paymentService:
        limitForPeriod: 100
        limitRefreshPeriod: 1s
```

---

### 10. API Gateway Pattern ⭐⭐⭐⭐

**Why Important**: Single entry point for clients, routing, authentication, rate limiting.

```java
@RestController
@RequestMapping("/api/gateway")
public class ApiGatewayController {
    @Autowired
    private OrderServiceClient orderService;
    
    @Autowired
    private InventoryServiceClient inventoryService;
    
    @Autowired
    private PaymentServiceClient paymentService;
    
    // Aggregation pattern
    @GetMapping("/orders/{orderId}/details")
    public OrderDetails getOrderDetails(@PathVariable String orderId) {
        // Call multiple services
        CompletableFuture<Order> orderFuture = 
            CompletableFuture.supplyAsync(() -> orderService.getOrder(orderId));
            
        CompletableFuture<Inventory> inventoryFuture = 
            CompletableFuture.supplyAsync(() -> inventoryService.getInventory(orderId));
            
        CompletableFuture<Payment> paymentFuture = 
            CompletableFuture.supplyAsync(() -> paymentService.getPayment(orderId));
        
        // Wait for all
        CompletableFuture.allOf(orderFuture, inventoryFuture, paymentFuture).join();
        
        // Aggregate results
        return new OrderDetails(
            orderFuture.join(),
            inventoryFuture.join(),
            paymentFuture.join()
        );
    }
    
    // Request routing
    @PostMapping("/process")
    public ResponseEntity<?> processRequest(@RequestBody GenericRequest request) {
        switch (request.getType()) {
            case "ORDER":
                return ResponseEntity.ok(orderService.process(request));
            case "PAYMENT":
                return ResponseEntity.ok(paymentService.process(request));
            case "INVENTORY":
                return ResponseEntity.ok(inventoryService.process(request));
            default:
                return ResponseEntity.badRequest().body("Unknown request type");
        }
    }
}
```

---

### 11. Service Registry & Discovery ⭐⭐⭐⭐⭐

**Why Critical**: Dynamic service location in distributed systems.

```java
// Using Spring Cloud Netflix Eureka

// Eureka Server
@SpringBootApplication
@EnableEurekaServer
public class ServiceRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceRegistryApplication.class, args);
    }
}

// Service registration
@SpringBootApplication
@EnableEurekaClient
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

// Service discovery and load balancing
@Service
public class OrderServiceClient {
    @Autowired
    private RestTemplate restTemplate; // Load-balanced
    
    @Autowired
    private DiscoveryClient discoveryClient;
    
    public Order getOrder(String orderId) {
        // Option 1: Using service name (load-balanced)
        return restTemplate.getForObject(
            "http://order-service/api/orders/" + orderId,
            Order.class
        );
    }
    
    public List<ServiceInstance> getOrderServiceInstances() {
        // Option 2: Manual discovery
        return discoveryClient.getInstances("order-service");
    }
}

// Using Feign Client (declarative REST client)
@FeignClient(name = "order-service")
public interface OrderServiceClient {
    @GetMapping("/api/orders/{orderId}")
    Order getOrder(@PathVariable String orderId);
    
    @PostMapping("/api/orders")
    Order createOrder(@RequestBody Order order);
}
```

---

## Event-Driven Architecture Patterns

### 12. Event Sourcing ⭐⭐⭐⭐

**Why Important**: Store state as sequence of events, enables audit trail and time travel.

```java
// Event
public abstract class Event {
    private String eventId;
    private String aggregateId;
    private LocalDateTime timestamp;
    private int version;
    
    // Constructor, getters
}

public class OrderCreatedEvent extends Event {
    private String customerId;
    private List<OrderItem> items;
    private double totalAmount;
}

public class OrderShippedEvent extends Event {
    private String trackingNumber;
    private LocalDateTime shippedAt;
}

// Aggregate
public class Order {
    private String orderId;
    private String customerId;
    private OrderStatus status;
    private List<OrderItem> items;
    private List<Event> changes = new ArrayList<>();
    
    // Apply event
    public void apply(Event event) {
        changes.add(event);
        mutate(event);
    }
    
    // Rebuild from events
    public static Order fromEvents(List<Event> events) {
        Order order = new Order();
        events.forEach(order::mutate);
        return order;
    }
    
    private void mutate(Event event) {
        if (event instanceof OrderCreatedEvent) {
            OrderCreatedEvent e = (OrderCreatedEvent) event;
            this.orderId = e.getAggregateId();
            this.customerId = e.getCustomerId();
            this.items = e.getItems();
            this.status = OrderStatus.CREATED;
        } else if (event instanceof OrderShippedEvent) {
            this.status = OrderStatus.SHIPPED;
        }
        // Handle other events
    }
    
    public List<Event> getUncommittedChanges() {
        return new ArrayList<>(changes);
    }
    
    public void markChangesAsCommitted() {
        changes.clear();
    }
}

// Event Store
@Repository
public class EventStore {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    public void saveEvents(String aggregateId, List<Event> events, int expectedVersion) {
        for (Event event : events) {
            jdbcTemplate.update(
                "INSERT INTO events (event_id, aggregate_id, event_type, data, version, timestamp) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                event.getEventId(),
                aggregateId,
                event.getClass().getSimpleName(),
                serializeEvent(event),
                expectedVersion++,
                event.getTimestamp()
            );
        }
    }
    
    public List<Event> getEvents(String aggregateId) {
        return jdbcTemplate.query(
            "SELECT * FROM events WHERE aggregate_id = ? ORDER BY version",
            (rs, rowNum) -> deserializeEvent(rs.getString("data"), rs.getString("event_type")),
            aggregateId
        );
    }
    
    public List<Event> getEventsSince(String aggregateId, int version) {
        return jdbcTemplate.query(
            "SELECT * FROM events WHERE aggregate_id = ? AND version > ? ORDER BY version",
            (rs, rowNum) -> deserializeEvent(rs.getString("data"), rs.getString("event_type")),
            aggregateId,
            version
        );
    }
}

// Repository
@Service
public class OrderRepository {
    @Autowired
    private EventStore eventStore;
    
    public void save(Order order) {
        List<Event> changes = order.getUncommittedChanges();
        if (!changes.isEmpty()) {
            eventStore.saveEvents(order.getOrderId(), changes, order.getVersion());
            order.markChangesAsCommitted();
        }
    }
    
    public Order findById(String orderId) {
        List<Event> events = eventStore.getEvents(orderId);
        if (events.isEmpty()) {
            return null;
        }
        return Order.fromEvents(events);
    }
}

// Projection (Read Model)
@Service
public class OrderProjection {
    @EventListener
    public void on(OrderCreatedEvent event) {
        // Update read model
        OrderReadModel readModel = new OrderReadModel();
        readModel.setOrderId(event.getAggregateId());
        readModel.setCustomerId(event.getCustomerId());
        readModel.setStatus("CREATED");
        orderReadRepository.save(readModel);
    }
    
    @EventListener
    public void on(OrderShippedEvent event) {
        OrderReadModel readModel = orderReadRepository.findById(event.getAggregateId());
        readModel.setStatus("SHIPPED");
        orderReadRepository.save(readModel);
    }
}
```

---

### 13. CQRS (Command Query Responsibility Segregation) ⭐⭐⭐⭐⭐

**Why Critical**: Separate read and write models for scalability and performance.

```java
// Commands (Write Model)
public interface Command {
    String getAggregateId();
}

public class CreateOrderCommand implements Command {
    private String orderId;
    private String customerId;
    private List<OrderItem> items;
    
    public String getAggregateId() { return orderId; }
}

public class ShipOrderCommand implements Command {
    private String orderId;
    private String trackingNumber;
    
    public String getAggregateId() { return orderId; }
}

// Command Handler
@Service
public class OrderCommandHandler {
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private EventBus eventBus;
    
    public void handle(CreateOrderCommand command) {
        // Create aggregate
        Order order = new Order(command.getOrderId());
        order.create(command.getCustomerId(), command.getItems());
        
        // Save
        orderRepository.save(order);
        
        // Publish events
        order.getUncommittedChanges().forEach(eventBus::publish);
    }
    
    public void handle(ShipOrderCommand command) {
        // Load aggregate
        Order order = orderRepository.findById(command.getAggregateId());
        
        // Execute command
        order.ship(command.getTrackingNumber());
        
        // Save
        orderRepository.save(order);
        
        // Publish events
        order.getUncommittedChanges().forEach(eventBus::publish);
    }
}

// Queries (Read Model)
public class OrderQuery {
    private String orderId;
    private String customerId;
    private String status;
}

// Query Handler
@Service
public class OrderQueryHandler {
    @Autowired
    private OrderReadRepository readRepository;
    
    public OrderReadModel getOrderById(String orderId) {
        return readRepository.findById(orderId);
    }
    
    public List<OrderReadModel> getOrdersByCustomer(String customerId) {
        return readRepository.findByCustomerId(customerId);
    }
    
    public List<OrderReadModel> getOrdersByStatus(String status) {
        return readRepository.findByStatus(status);
    }
}

// Separate databases
// Write Model: Event Store (Write-optimized)
// Read Model: Denormalized views (Read-optimized, can be NoSQL)

// API Layer
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    @Autowired
    private OrderCommandHandler commandHandler;
    
    @Autowired
    private OrderQueryHandler queryHandler;
    
    // Write side
    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = new CreateOrderCommand(
            UUID.randomUUID().toString(),
            request.getCustomerId(),
            request.getItems()
        );
        
        commandHandler.handle(command);
        return ResponseEntity.ok(command.getAggregateId());
    }
    
    // Read side
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderReadModel> getOrder(@PathVariable String orderId) {
        OrderReadModel order = queryHandler.getOrderById(orderId);
        return ResponseEntity.ok(order);
    }
    
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<OrderReadModel>> getCustomerOrders(
            @PathVariable String customerId) {
        List<OrderReadModel> orders = queryHandler.getOrdersByCustomer(customerId);
        return ResponseEntity.ok(orders);
    }
}
```

---

## Data Management Patterns

### 14. Database per Service ⭐⭐⭐⭐⭐

**Why Critical**: Each microservice owns its data, enabling independent scaling and deployment.

```java
// Order Service - Has its own database
@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository; // Orders database
    
    @Autowired
    private EventPublisher eventPublisher;
    
    public Order createOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        order.setItems(request.getItems());
        
        // Save to own database
        orderRepository.save(order);
        
        // Publish event for other services
        eventPublisher.publish(new OrderCreatedEvent(order));
        
        return order;
    }
}

// Inventory Service - Has its own database
@Service
public class InventoryService {
    @Autowired
    private InventoryRepository inventoryRepository; // Inventory database
    
    @EventListener
    public void handleOrderCreated(OrderCreatedEvent event) {
        // Update own database
        for (OrderItem item : event.getItems()) {
            Inventory inventory = inventoryRepository.findByProductId(item.getProductId());
            inventory.setQuantity(inventory.getQuantity() - item.getQuantity());
            inventoryRepository.save(inventory);
        }
    }
}

// Customer Service - Has its own database
@Service
public class CustomerService {
    @Autowired
    private CustomerRepository customerRepository; // Customer database
    
    public Customer getCustomer(String customerId) {
        return customerRepository.findById(customerId);
    }
}
```

---

### 15. Outbox Pattern ⭐⭐⭐⭐⭐

**Why Critical**: Ensures reliable event publishing with database transactions (solves dual-write problem).

```java
// Outbox table
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    private String id;
    private String aggregateId;
    private String eventType;
    private String payload;
    private LocalDateTime createdAt;
    private boolean published;
}

// Service with Outbox
@Service
public class OrderService {
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private OutboxRepository outboxRepository;
    
    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        // 1. Save order to database
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        orderRepository.save(order);
        
        // 2. Save event to outbox table (same transaction!)
        OrderCreatedEvent event = new OrderCreatedEvent(order);
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID().toString());
        outboxEvent.setAggregateId(order.getId());
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setPayload(serializeEvent(event));
        outboxEvent.setCreatedAt(LocalDateTime.now());
        outboxEvent.setPublished(false);
        
        outboxRepository.save(outboxEvent);
        
        // Both save operations succeed or fail together!
        return order;
    }
}

// Outbox Publisher (separate process/scheduler)
@Component
public class OutboxPublisher {
    @Autowired
    private OutboxRepository outboxRepository;
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Transactional
    public void publishEvents() {
        // 1. Fetch unpublished events
        List<OutboxEvent> events = outboxRepository.findByPublishedFalse();
        
        for (OutboxEvent event : events) {
            try {
                // 2. Publish to message broker
                kafkaTemplate.send("events", event.getAggregateId(), event.getPayload());
                
                // 3. Mark as published
                event.setPublished(true);
                outboxRepository.save(event);
                
            } catch (Exception e) {
                // Will retry on next iteration
                log.error("Failed to publish event: " + event.getId(), e);
            }
        }
    }
}

// Alternative: Change Data Capture (CDC) with Debezium
// Debezium monitors database transaction log and publishes changes automatically
```

---

## Resilience Patterns

### 16. Bulkhead Pattern ⭐⭐⭐⭐

**Why Important**: Isolate resources to prevent cascading failures.

```java
// Thread pool bulkheads
@Configuration
public class BulkheadConfig {
    @Bean
    public ThreadPoolTaskExecutor orderServiceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("order-service-");
        executor.initialize();
        return executor;
    }
    
    @Bean
    public ThreadPoolTaskExecutor paymentServiceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("payment-service-");
        executor.initialize();
        return executor;
    }
}

@Service
public class OrderService {
    @Autowired
    @Qualifier("orderServiceExecutor")
    private ThreadPoolTaskExecutor executor;
    
    @Async("orderServiceExecutor")
    @Bulkhead(name = "orderService", type = Bulkhead.Type.THREADPOOL)
    public CompletableFuture<Order> processOrder(String orderId) {
        // This runs in isolated thread pool
        // If order service is slow, it won't affect payment service
        return CompletableFuture.completedFuture(orderRepository.findById(orderId));
    }
}

// Resilience4j Bulkhead
resilience4j:
  bulkhead:
    instances:
      orderService:
        maxConcurrentCalls: 10
        maxWaitDuration: 1s
      paymentService:
        maxConcurrentCalls: 5
        maxWaitDuration: 500ms
```

---

### 17. Timeout Pattern ⭐⭐⭐⭐

**Why Important**: Prevent indefinite waits in distributed systems.

```java
@Service
public class OrderService {
    @Autowired
    private RestTemplate restTemplate;
    
    @TimeLimiter(name = "inventoryService")
    public CompletableFuture<Inventory> checkInventory(String productId) {
        return CompletableFuture.supplyAsync(() -> 
            restTemplate.getForObject(
                "http://inventory-service/products/" + productId,
                Inventory.class
            )
        );
    }
}

// Configuration
resilience4j:
  timelimiter:
    instances:
      inventoryService:
        timeoutDuration: 2s
        cancelRunningFuture: true
```

---

## Real-World Implementation Examples

### Complete E-Commerce Order Flow

```java
// 1. API Gateway receives request
@RestController
public class OrderGatewayController {
    @Autowired
    private OrderCommandHandler commandHandler;
    
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest request) {
        // Create command
        CreateOrderCommand command = new CreateOrderCommand(
            UUID.randomUUID().toString(),
            request.getCustomerId(),
            request.getItems()
        );
        
        // Execute command
        String orderId = commandHandler.handle(command);
        
        return ResponseEntity.accepted()
            .body(new OrderResponse(orderId, "Order accepted for processing"));
    }
}

// 2. Order Service - Command Handler
@Service
public class OrderCommandHandler {
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private OutboxRepository outboxRepository;
    
    @Transactional
    public String handle(CreateOrderCommand command) {
        // Create order aggregate
        Order order = new Order();
        order.create(command);
        
        // Save order
        orderRepository.save(order);
        
        // Save event to outbox (same transaction)
        OrderCreatedEvent event = new OrderCreatedEvent(order);
        saveToOutbox(event);
        
        return order.getId();
    }
}

// 3. Outbox Publisher publishes event
@Component
public class OutboxPublisher {
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findUnpublished();
        
        events.forEach(event -> {
            kafkaTemplate.send("order-events", event.getPayload());
            event.setPublished(true);
            outboxRepository.save(event);
        });
    }
}

// 4. Inventory Service listens and responds (Saga choreography)
@Service
public class InventorySagaHandler {
    @Autowired
    private InventoryRepository inventoryRepository;
    
    @Autowired
    private EventPublisher eventPublisher;
    
    @KafkaListener(topics = "order-events")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            // Reserve inventory
            reserveItems(event.getItems());
            
            // Publish success event
            eventPublisher.publish(new InventoryReservedEvent(event.getOrderId()));
            
        } catch (InsufficientStockException e) {
            // Publish failure event
            eventPublisher.publish(new InventoryReservationFailedEvent(
                event.getOrderId(), 
                e.getMessage()
            ));
        }
    }
    
    // Compensating action
    @KafkaListener(topics = "order-events")
    public void handleOrderCancelled(OrderCancelledEvent event) {
        releaseReservation(event.getOrderId());
    }
}

// 5. Payment Service processes payment
@Service
public class PaymentSagaHandler {
    @Autowired
    private PaymentGateway paymentGateway;
    
    @Autowired
    private EventPublisher eventPublisher;
    
    @KafkaListener(topics = "order-events")
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "paymentFallback")
    @Retry(name = "paymentGateway")
    public void handleInventoryReserved(InventoryReservedEvent event) {
        try {
            // Process payment
            paymentGateway.charge(event.getOrderId(), event.getAmount());
            
            // Publish success
            eventPublisher.publish(new PaymentProcessedEvent(event.getOrderId()));
            
        } catch (PaymentException e) {
            // Publish failure
            eventPublisher.publish(new PaymentFailedEvent(
                event.getOrderId(),
                e.getMessage()
            ));
        }
    }
    
    // Fallback
    private void paymentFallback(InventoryReservedEvent event, Exception e) {
        eventPublisher.publish(new PaymentFailedEvent(
            event.getOrderId(),
            "Payment service unavailable"
        ));
    }
}

// 6. Order Service completes saga
@Service
public class OrderSagaCompletionHandler {
    @Autowired
    private OrderRepository orderRepository;
    
    @KafkaListener(topics = "order-events")
    @Transactional
    public void handlePaymentProcessed(PaymentProcessedEvent event) {
        Order order = orderRepository.findById(event.getOrderId());
        order.confirm();
        orderRepository.save(order);
        
        // Publish order confirmed event
        eventPublisher.publish(new OrderConfirmedEvent(event.getOrderId()));
    }
    
    @KafkaListener(topics = "order-events")
    @Transactional
    public void handleSagaFailure(Object failureEvent) {
        String orderId = extractOrderId(failureEvent);
        Order order = orderRepository.findById(orderId);
        order.cancel();
        orderRepository.save(order);
        
        // Trigger compensating actions
        eventPublisher.publish(new OrderCancelledEvent(orderId));
    }
}

// 7. Notification Service sends notifications
@Service
public class NotificationService {
    @KafkaListener(topics = "order-events")
    @Async
    public void handleOrderConfirmed(OrderConfirmedEvent event) {
        // Send email
        emailService.sendOrderConfirmation(event.getOrderId());
        
        // Send SMS
        smsService.sendNotification(event.getOrderId());
    }
}

// 8. Query side - Read model projection
@Service
public class OrderReadModelProjection {
    @Autowired
    private OrderReadRepository readRepository;
    
    @KafkaListener(topics = "order-events")
    public void projectOrderCreated(OrderCreatedEvent event) {
        OrderReadModel readModel = new OrderReadModel();
        readModel.setOrderId(event.getOrderId());
        readModel.setCustomerId(event.getCustomerId());
        readModel.setStatus("PENDING");
        readRepository.save(readModel);
    }
    
    @KafkaListener(topics = "order-events")
    public void projectOrderConfirmed(OrderConfirmedEvent event) {
        OrderReadModel readModel = readRepository.findById(event.getOrderId());
        readModel.setStatus("CONFIRMED");
        readModel.setConfirmedAt(LocalDateTime.now());
        readRepository.save(readModel);
    }
}

// 9. Query API for customers
@RestController
public class OrderQueryController {
    @Autowired
    private OrderReadRepository readRepository;
    
    @GetMapping("/orders/{orderId}")
    @Cacheable("orders")
    public ResponseEntity<OrderReadModel> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(readRepository.findById(orderId));
    }
}
```

---

## Pattern Combinations

### Event-Driven Microservices Stack

```
┌─────────────────────────────────────────────────────────┐
│                    API Gateway                          │
│  (Adapter, Facade, Circuit Breaker, Rate Limiting)     │
└─────────────────────────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
┌───────▼───────┐ ┌─────▼──────┐ ┌──────▼──────┐
│ Order Service │ │  Inventory  │ │   Payment   │
│ (Command,     │ │  Service    │ │   Service   │
│  Observer,    │ │  (Observer, │ │  (Observer, │
│  Saga,        │ │   Strategy) │ │   Proxy,    │
│  CQRS)        │ │             │ │   Circuit   │
│               │ │             │ │   Breaker)  │
└───────┬───────┘ └─────┬──────┘ └──────┬──────┘
        │               │               │
        │      ┌────────▼───────────────▼────────┐
        └──────►   Event Bus (Kafka/RabbitMQ)    │
               │  (Observer, Outbox Pattern)     │
               └────────┬────────────────┬────────┘
                        │                │
               ┌────────▼────────┐ ┌────▼─────────┐
               │ Notification    │ │   Analytics  │
               │ Service         │ │   Service    │
               │ (Observer)      │ │   (Observer) │
               └─────────────────┘ └──────────────┘
```

---

## Summary: Pattern Importance for Event-Based Microservices

### ⭐⭐⭐⭐⭐ Must Know (Critical)
1. **Observer** - Foundation of event-driven systems
2. **Command** - CQRS, event sourcing, request handling
3. **Saga** - Distributed transactions
4. **Circuit Breaker** - Resilience and fault tolerance
5. **Service Registry** - Service discovery
6. **CQRS** - Scalable read/write separation
7. **Outbox** - Reliable event publishing
8. **Database per Service** - Service autonomy

### ⭐⭐⭐⭐ Should Know (Important)
9. **Strategy** - Message routing, retry policies
10. **Adapter** - Protocol integration
11. **Chain of Responsibility** - Message filtering
12. **Bulkhead** - Resource isolation
13. **Timeout** - Prevent indefinite waits
14. **Event Sourcing** - Audit trail, time travel
15. **API Gateway** - Single entry point

### ⭐⭐⭐ Good to Know
16. **Factory** - Event/handler creation
17. **Proxy** - Caching, logging, cross-cutting concerns
18. **State** - Saga state management
19. **Composite** - Aggregate events

---

## Best Practices

1. **Start Simple**: Don't use all patterns at once
2. **Event First**: Design events before implementing services
3. **Idempotency**: Make event handlers idempotent
4. **Monitoring**: Add observability from day one
5. **Testing**: Test each service independently
6. **Documentation**: Maintain event catalog and service contracts

---

## Tools & Frameworks

- **Event Streaming**: Apache Kafka, RabbitMQ, AWS SNS/SQS
- **Service Mesh**: Istio, Linkerd
- **API Gateway**: Kong, Spring Cloud Gateway
- **Service Discovery**: Eureka, Consul
- **Circuit Breaker**: Resilience4j, Hystrix
- **Event Sourcing**: Axon Framework, Eventuate
- **CDC**: Debezium
- **Monitoring**: Prometheus, Grafana, ELK Stack
- **Tracing**: Jaeger, Zipkin

---

**Remember**: Event-based microservices require understanding of distributed systems challenges. Master these patterns to build resilient, scalable systems!
