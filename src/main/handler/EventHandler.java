package com.enterprise.eventhub.service.handler;

import com.azure.messaging.eventhubs.EventData;
import com.enterprise.eventhub.domain.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Event Handler - Routes and processes events by type
 * 
 * 5W1H Analysis:
 * WHO: Event Hub Consumer Service delegates to this handler
 * WHAT: Deserializes and routes events to appropriate processors
 * WHEN: Called for each event consumed from Event Hub
 * WHERE: Business logic layer
 * WHY: Separates event routing from consumption logic
 * HOW: Uses strategy pattern with type-based routing
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventHandler {
    
    private final ObjectMapper objectMapper;
    private final OrderEventHandler orderEventHandler;
    private final PaymentEventHandler paymentEventHandler;
    private final InventoryEventHandler inventoryEventHandler;
    private final UserEventHandler userEventHandler;
    private final DeadLetterHandler deadLetterHandler;
    
    /**
     * Main event handling entry point
     * 
     * @param eventType Type of event (OrderCreated, PaymentProcessed, etc.)
     * @param payload JSON payload
     * @param eventData Original Event Hub event data
     * @return true if processed successfully, false otherwise
     */
    public boolean handleEvent(String eventType, String payload, EventData eventData) {
        log.info("Handling event: type={}", eventType);
        
        try {
            return switch (eventType) {
                case "OrderCreated" -> handleOrderCreated(payload, eventData);
                case "PaymentProcessed" -> handlePaymentProcessed(payload, eventData);
                case "InventoryUpdated" -> handleInventoryUpdated(payload, eventData);
                case "UserRegistered" -> handleUserRegistered(payload, eventData);
                default -> handleUnknownEvent(eventType, payload, eventData);
            };
            
        } catch (Exception e) {
            log.error("Error handling event: type={}", eventType, e);
            return false;
        }
    }
    
    /**
     * Handle Order Created Event
     */
    private boolean handleOrderCreated(String payload, EventData eventData) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(
                payload, 
                OrderCreatedEvent.class
            );
            
            return orderEventHandler.handleOrderCreated(event);
            
        } catch (Exception e) {
            log.error("Error deserializing OrderCreated event", e);
            return false;
        }
    }
    
    /**
     * Handle Payment Processed Event
     */
    private boolean handlePaymentProcessed(String payload, EventData eventData) {
        try {
            PaymentProcessedEvent event = objectMapper.readValue(
                payload,
                PaymentProcessedEvent.class
            );
            
            return paymentEventHandler.handlePaymentProcessed(event);
            
        } catch (Exception e) {
            log.error("Error deserializing PaymentProcessed event", e);
            return false;
        }
    }
    
    /**
     * Handle Inventory Updated Event
     */
    private boolean handleInventoryUpdated(String payload, EventData eventData) {
        try {
            InventoryUpdatedEvent event = objectMapper.readValue(
                payload,
                InventoryUpdatedEvent.class
            );
            
            return inventoryEventHandler.handleInventoryUpdated(event);
            
        } catch (Exception e) {
            log.error("Error deserializing InventoryUpdated event", e);
            return false;
        }
    }
    
    /**
     * Handle User Registered Event
     */
    private boolean handleUserRegistered(String payload, EventData eventData) {
        try {
            UserRegisteredEvent event = objectMapper.readValue(
                payload,
                UserRegisteredEvent.class
            );
            
            return userEventHandler.handleUserRegistered(event);
            
        } catch (Exception e) {
            log.error("Error deserializing UserRegistered event", e);
            return false;
        }
    }
    
    /**
     * Handle unknown event types
     * 
     * Could be:
     * - New event types not yet implemented
     * - Events from other services
     * - Malformed events
     */
    private boolean handleUnknownEvent(
        String eventType, 
        String payload, 
        EventData eventData
    ) {
        log.warn("Unknown event type received: type={}", eventType);
        
        // Log for analysis
        log.debug("Unknown event payload: {}", payload);
        
        // Could store in database for later processing
        // Or send to separate unknown events topic
        
        // Return true to acknowledge (prevent reprocessing)
        return true;
    }
    
    /**
     * Handle dead letter events
     * 
     * Called when max retries exceeded
     */
    public void handleDeadLetter(String payload, String errorMessage) {
        deadLetterHandler.handleDeadLetter(payload, errorMessage);
    }
}

/**
 * Individual Event Type Handlers
 * 
 * Each handler implements business logic for specific event type
 */

@Component
@Slf4j
class OrderEventHandler {
    
    public boolean handleOrderCreated(OrderCreatedEvent event) {
        log.info("Processing OrderCreated: orderId={}, customerId={}, amount={}", 
            event.getOrderId(), event.getCustomerId(), event.getTotalAmount());
        
        try {
            // Business logic here:
            // - Update read model
            // - Trigger notifications
            // - Update analytics
            // - Start fulfillment process
            
            log.info("OrderCreated processed successfully: orderId={}", 
                event.getOrderId());
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to process OrderCreated: orderId={}", 
                event.getOrderId(), e);
            return false;
        }
    }
}

@Component
@Slf4j
class PaymentEventHandler {
    
    public boolean handlePaymentProcessed(PaymentProcessedEvent event) {
        log.info("Processing PaymentProcessed: paymentId={}, orderId={}, amount={}", 
            event.getPaymentId(), event.getOrderId(), event.getAmount());
        
        try {
            // Business logic here:
            // - Update order status
            // - Send confirmation email
            // - Update accounting system
            // - Release inventory hold
            
            log.info("PaymentProcessed processed successfully: paymentId={}", 
                event.getPaymentId());
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to process PaymentProcessed: paymentId={}", 
                event.getPaymentId(), e);
            return false;
        }
    }
}

@Component
@Slf4j
class InventoryEventHandler {
    
    public boolean handleInventoryUpdated(InventoryUpdatedEvent event) {
        log.info("Processing InventoryUpdated: productId={}, oldQty={}, newQty={}", 
            event.getProductId(), event.getPreviousQuantity(), event.getNewQuantity());
        
        try {
            // Business logic here:
            // - Update product availability
            // - Trigger reorder if low stock
            // - Update search index
            // - Notify interested parties
            
            log.info("InventoryUpdated processed successfully: productId={}", 
                event.getProductId());
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to process InventoryUpdated: productId={}", 
                event.getProductId(), e);
            return false;
        }
    }
}

@Component
@Slf4j
class UserEventHandler {
    
    public boolean handleUserRegistered(UserRegisteredEvent event) {
        log.info("Processing UserRegistered: userId={}, email={}", 
            event.getUserId(), event.getEmail());
        
        try {
            // Business logic here:
            // - Send welcome email
            // - Create user profile
            // - Add to marketing lists
            // - Initialize preferences
            
            log.info("UserRegistered processed successfully: userId={}", 
                event.getUserId());
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to process UserRegistered: userId={}", 
                event.getUserId(), e);
            return false;
        }
    }
}

@Component
@Slf4j
class DeadLetterHandler {
    
    public void handleDeadLetter(String payload, String errorMessage) {
        log.error("Event moved to dead letter queue: error={}", errorMessage);
        log.debug("Dead letter payload: {}", payload);
        
        // Store in dead letter database table
        // Or send to separate dead letter Event Hub
        // Alert operations team
        
        // Example: Store for manual review
        // deadLetterRepository.save(new DeadLetterEvent(payload, errorMessage));
    }
}
