package com.enterprise.eventhub.service;

import com.enterprise.eventhub.domain.entity.Order;
import com.enterprise.eventhub.domain.event.OrderCreatedEvent;
import com.enterprise.eventhub.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Order Service - Business Service Example
 * 
 * 5W1H Analysis:
 * WHO: REST controllers and other services
 * WHAT: Handles order creation business logic with event publishing
 * WHEN: When customer places an order
 * WHERE: Business service layer
 * WHY: Demonstrates transactional outbox pattern in practice
 * HOW: Saves order and publishes event in single transaction
 * 
 * Key Pattern: Transactional Outbox
 * - Single @Transactional method
 * - Business logic + event publishing share transaction
 * - Both succeed or both rollback (atomicity)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    
    /**
     * Create new order with event publishing
     * 
     * Transaction Boundary:
     * 1. Validate order
     * 2. Save order to database
     * 3. Publish OrderCreated event to outbox
     * 4. Both operations in same transaction
     * 
     * If any step fails, entire transaction rolls back
     * This ensures consistency between order state and events
     * 
     * @param request Order creation request
     * @return Created order
     */
    @Transactional
    public Order createOrder(CreateOrderRequest request) {
        log.info("Creating order: customerId={}, items={}", 
            request.getCustomerId(), request.getItemCount());
        
        try {
            // Step 1: Validate business rules
            validateOrder(request);
            
            // Step 2: Create order entity
            Order order = Order.builder()
                .id(UUID.randomUUID().toString())
                .customerId(request.getCustomerId())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .itemCount(request.getItemCount())
                .shippingAddress(request.getShippingAddress())
                .status("CREATED")
                .createdAt(LocalDateTime.now())
                .build();
            
            // Step 3: Save to database
            Order savedOrder = orderRepository.save(order);
            
            log.debug("Order saved: orderId={}", savedOrder.getId());
            
            // Step 4: Create domain event
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                .eventType("OrderCreated")
                .orderId(savedOrder.getId())
                .customerId(savedOrder.getCustomerId())
                .totalAmount(savedOrder.getTotalAmount())
                .currency(savedOrder.getCurrency())
                .itemCount(savedOrder.getItemCount())
                .shippingAddress(savedOrder.getShippingAddress())
                .orderStatus(savedOrder.getStatus())
                .version("1.0")
                .correlationId(UUID.randomUUID())
                .triggeredBy(request.getUserId())
                .build();
            
            // Step 5: Publish to outbox (same transaction)
            // CRITICAL: OutboxService.publishEvent uses Propagation.MANDATORY
            // This ensures it participates in this transaction
            outboxService.publishEvent(
                event,
                savedOrder.getId(),  // aggregateId
                "ORDER"              // aggregateType
            );
            
            log.info("Order created successfully: orderId={}", savedOrder.getId());
            
            // If we reach here, both order save and event publish succeeded
            // Transaction will commit
            return savedOrder;
            
        } catch (Exception e) {
            log.error("Failed to create order: customerId={}", 
                request.getCustomerId(), e);
            
            // Any exception causes transaction rollback
            // Neither order nor event will be persisted
            throw new OrderCreationException("Failed to create order", e);
        }
    }
    
    /**
     * Update order status with event
     * 
     * Similar pattern: State change + event in same transaction
     */
    @Transactional
    public Order updateOrderStatus(String orderId, String newStatus, String userId) {
        log.info("Updating order status: orderId={}, newStatus={}", 
            orderId, newStatus);
        
        // Fetch order
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        String oldStatus = order.getStatus();
        
        // Update status
        order.setStatus(newStatus);
        order.setUpdatedAt(LocalDateTime.now());
        
        // Save
        Order updated = orderRepository.save(order);
        
        // Publish status change event
        // (Would create OrderStatusChanged event here)
        // outboxService.publishEvent(event, orderId, "ORDER");
        
        log.info("Order status updated: orderId={}, {} -> {}", 
            orderId, oldStatus, newStatus);
        
        return updated;
    }
    
    /**
     * Cancel order with compensation event
     */
    @Transactional
    public void cancelOrder(String orderId, String reason, String userId) {
        log.info("Cancelling order: orderId={}, reason={}", orderId, reason);
        
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        
        // Validate cancellation
        if (!"CREATED".equals(order.getStatus()) && !"PENDING".equals(order.getStatus())) {
            throw new OrderCancellationException(
                "Cannot cancel order in status: " + order.getStatus()
            );
        }
        
        // Update status
        order.setStatus("CANCELLED");
        order.setCancellationReason(reason);
        order.setUpdatedAt(LocalDateTime.now());
        
        orderRepository.save(order);
        
        // Publish cancellation event
        // This would trigger compensation in other services
        // (inventory release, payment refund, etc.)
        // OrderCancelledEvent event = ...
        // outboxService.publishEvent(event, orderId, "ORDER");
        
        log.info("Order cancelled: orderId={}", orderId);
    }
    
    /**
     * Validate order business rules
     */
    private void validateOrder(CreateOrderRequest request) {
        if (request.getTotalAmount() <= 0) {
            throw new OrderValidationException("Invalid order amount");
        }
        
        if (request.getItemCount() <= 0) {
            throw new OrderValidationException("Order must have items");
        }
        
        // More validation...
    }
    
    /**
     * Request DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class CreateOrderRequest {
        private String customerId;
        private Double totalAmount;
        private String currency;
        private Integer itemCount;
        private String shippingAddress;
        private String userId; // Who created the order
    }
    
    // Custom exceptions
    public static class OrderCreationException extends RuntimeException {
        public OrderCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String message) {
            super(message);
        }
    }
    
    public static class OrderValidationException extends RuntimeException {
        public OrderValidationException(String message) {
            super(message);
        }
    }
    
    public static class OrderCancellationException extends RuntimeException {
        public OrderCancellationException(String message) {
            super(message);
        }
    }
}
