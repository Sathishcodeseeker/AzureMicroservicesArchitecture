package com.enterprise.eventhub.domain.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Base Event DTO
 * 
 * 5W1H Analysis:
 * WHO: All domain events inherit from this
 * WHAT: Common event structure for all business events
 * WHEN: Created during business operations
 * WHERE: Business service layer
 * WHY: Ensures consistency and enables polymorphic deserialization
 * HOW: Uses Jackson annotations for JSON serialization
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "eventType"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = OrderCreatedEvent.class, name = "OrderCreated"),
    @JsonSubTypes.Type(value = PaymentProcessedEvent.class, name = "PaymentProcessed"),
    @JsonSubTypes.Type(value = InventoryUpdatedEvent.class, name = "InventoryUpdated"),
    @JsonSubTypes.Type(value = UserRegisteredEvent.class, name = "UserRegistered")
})
@Data
@SuperBuilder
@NoArgsConstructor
public abstract class BaseEvent {
    
    /**
     * Unique event identifier
     */
    private UUID eventId;
    
    /**
     * Event type discriminator
     */
    private String eventType;
    
    /**
     * When the event occurred
     */
    private LocalDateTime occurredAt;
    
    /**
     * Version for event schema evolution
     */
    private String version;
    
    /**
     * Correlation ID for distributed tracing
     */
    private UUID correlationId;
    
    /**
     * Causation ID - ID of the command/event that caused this event
     */
    private UUID causationId;
    
    /**
     * User/System that triggered the event
     */
    private String triggeredBy;
}

/**
 * Order Created Event
 * 
 * Fired when a new order is placed
 */
@Data
@SuperBuilder
@NoArgsConstructor
class OrderCreatedEvent extends BaseEvent {
    private String orderId;
    private String customerId;
    private Double totalAmount;
    private String currency;
    private Integer itemCount;
    private String shippingAddress;
    private String orderStatus;
}

/**
 * Payment Processed Event
 * 
 * Fired when a payment is successfully processed
 */
@Data
@SuperBuilder
@NoArgsConstructor
class PaymentProcessedEvent extends BaseEvent {
    private String paymentId;
    private String orderId;
    private String customerId;
    private Double amount;
    private String currency;
    private String paymentMethod;
    private String transactionId;
    private String paymentStatus;
}

/**
 * Inventory Updated Event
 * 
 * Fired when inventory levels change
 */
@Data
@SuperBuilder
@NoArgsConstructor
class InventoryUpdatedEvent extends BaseEvent {
    private String productId;
    private String sku;
    private Integer previousQuantity;
    private Integer newQuantity;
    private Integer quantityChange;
    private String warehouseId;
    private String updateReason;
}

/**
 * User Registered Event
 * 
 * Fired when a new user registers
 */
@Data
@SuperBuilder
@NoArgsConstructor
class UserRegisteredEvent extends BaseEvent {
    private String userId;
    private String email;
    private String firstName;
    private String lastName;
    private String country;
    private String registrationSource;
    private Boolean emailVerified;
}
