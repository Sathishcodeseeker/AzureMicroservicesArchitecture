package com.enterprise.eventhub.controller;

import com.enterprise.eventhub.domain.entity.Order;
import com.enterprise.eventhub.service.OrderService;
import com.enterprise.eventhub.service.OrderService.CreateOrderRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Order Controller - REST API for Order Operations
 * 
 * 5W1H Analysis:
 * WHO: Frontend applications, mobile apps, external systems
 * WHAT: Exposes HTTP endpoints for order management
 * WHEN: On customer actions (place order, cancel, etc.)
 * WHERE: API Gateway / Load Balancer routes to this controller
 * WHY: Provides REST interface to order business logic
 * HOW: Spring MVC with resilience patterns and validation
 * 
 * NFR Implementation:
 * 1. Input validation with Bean Validation
 * 2. Circuit breaker for downstream dependencies
 * 3. Rate limiting for DDoS protection
 * 4. Structured error responses
 * 5. API versioning
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {
    
    private final OrderService orderService;
    
    /**
     * Create new order
     * 
     * Endpoint: POST /api/v1/orders
     * 
     * NFR Applied:
     * - @Valid: Input validation
     * - @CircuitBreaker: Protects against downstream failures
     * - @RateLimiter: Prevents abuse
     * 
     * @param request Order creation request
     * @return Created order with 201 status
     */
    @PostMapping
    @CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
    @RateLimiter(name = "orderService")
    public ResponseEntity<OrderResponse> createOrder(
        @Valid @RequestBody CreateOrderRequestDto request
    ) {
        log.info("Received create order request: customerId={}", 
            request.getCustomerId());
        
        try {
            // Convert DTO to service request
            CreateOrderRequest serviceRequest = CreateOrderRequest.builder()
                .customerId(request.getCustomerId())
                .totalAmount(request.getTotalAmount())
                .currency(request.getCurrency())
                .itemCount(request.getItemCount())
                .shippingAddress(request.getShippingAddress())
                .userId(request.getUserId())
                .build();
            
            // Create order
            Order order = orderService.createOrder(serviceRequest);
            
            // Convert to response
            OrderResponse response = OrderResponse.fromEntity(order);
            
            log.info("Order created successfully: orderId={}", order.getId());
            
            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
            
        } catch (OrderService.OrderValidationException e) {
            log.warn("Order validation failed: {}", e.getMessage());
            throw new InvalidOrderException(e.getMessage());
            
        } catch (Exception e) {
            log.error("Error creating order", e);
            throw new OrderCreationFailedException("Failed to create order");
        }
    }
    
    /**
     * Get order by ID
     * 
     * Endpoint: GET /api/v1/orders/{orderId}
     */
    @GetMapping("/{orderId}")
    @CircuitBreaker(name = "orderService")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        log.info("Fetching order: orderId={}", orderId);
        
        // Implementation would fetch from repository
        // For brevity, showing structure only
        
        return ResponseEntity.ok(new OrderResponse());
    }
    
    /**
     * Update order status
     * 
     * Endpoint: PATCH /api/v1/orders/{orderId}/status
     */
    @PatchMapping("/{orderId}/status")
    @CircuitBreaker(name = "orderService")
    @RateLimiter(name = "orderService")
    public ResponseEntity<OrderResponse> updateStatus(
        @PathVariable String orderId,
        @Valid @RequestBody UpdateStatusRequest request
    ) {
        log.info("Updating order status: orderId={}, newStatus={}", 
            orderId, request.getStatus());
        
        Order updated = orderService.updateOrderStatus(
            orderId,
            request.getStatus(),
            request.getUserId()
        );
        
        return ResponseEntity.ok(OrderResponse.fromEntity(updated));
    }
    
    /**
     * Cancel order
     * 
     * Endpoint: POST /api/v1/orders/{orderId}/cancel
     */
    @PostMapping("/{orderId}/cancel")
    @CircuitBreaker(name = "orderService")
    @RateLimiter(name = "orderService")
    public ResponseEntity<Map<String, String>> cancelOrder(
        @PathVariable String orderId,
        @Valid @RequestBody CancelOrderRequest request
    ) {
        log.info("Cancelling order: orderId={}, reason={}", 
            orderId, request.getReason());
        
        orderService.cancelOrder(
            orderId,
            request.getReason(),
            request.getUserId()
        );
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Order cancelled successfully");
        response.put("orderId", orderId);
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Fallback method for createOrder
     * 
     * Called when circuit breaker opens
     */
    @SuppressWarnings("unused")
    private ResponseEntity<OrderResponse> createOrderFallback(
        CreateOrderRequestDto request,
        Exception e
    ) {
        log.error("Circuit breaker fallback triggered for order creation", e);
        throw new ServiceUnavailableException(
            "Order service is temporarily unavailable. Please try again later."
        );
    }
    
    /**
     * Request DTOs with validation
     */
    @lombok.Data
    public static class CreateOrderRequestDto {
        
        @NotBlank(message = "Customer ID is required")
        private String customerId;
        
        @NotNull(message = "Total amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        private Double totalAmount;
        
        @NotBlank(message = "Currency is required")
        @Size(min = 3, max = 3, message = "Currency must be 3 characters")
        private String currency;
        
        @NotNull(message = "Item count is required")
        @Min(value = 1, message = "Must have at least 1 item")
        private Integer itemCount;
        
        @NotBlank(message = "Shipping address is required")
        @Size(max = 500, message = "Address too long")
        private String shippingAddress;
        
        @NotBlank(message = "User ID is required")
        private String userId;
    }
    
    @lombok.Data
    public static class UpdateStatusRequest {
        
        @NotBlank(message = "Status is required")
        private String status;
        
        @NotBlank(message = "User ID is required")
        private String userId;
    }
    
    @lombok.Data
    public static class CancelOrderRequest {
        
        @NotBlank(message = "Reason is required")
        @Size(max = 500)
        private String reason;
        
        @NotBlank(message = "User ID is required")
        private String userId;
    }
    
    /**
     * Response DTO
     */
    @lombok.Data
    @lombok.Builder
    public static class OrderResponse {
        private String orderId;
        private String customerId;
        private Double totalAmount;
        private String currency;
        private Integer itemCount;
        private String status;
        private String createdAt;
        
        public static OrderResponse fromEntity(Order order) {
            return OrderResponse.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .itemCount(order.getItemCount())
                .status(order.getStatus())
                .createdAt(order.getCreatedAt().toString())
                .build();
        }
    }
    
    /**
     * Exception Handlers
     */
    @ExceptionHandler(InvalidOrderException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOrder(InvalidOrderException e) {
        ErrorResponse error = new ErrorResponse(
            "INVALID_ORDER",
            e.getMessage()
        );
        return ResponseEntity.badRequest().body(error);
    }
    
    @ExceptionHandler(OrderService.OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
        OrderService.OrderNotFoundException e
    ) {
        ErrorResponse error = new ErrorResponse(
            "ORDER_NOT_FOUND",
            e.getMessage()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }
    
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(
        ServiceUnavailableException e
    ) {
        ErrorResponse error = new ErrorResponse(
            "SERVICE_UNAVAILABLE",
            e.getMessage()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
    }
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ErrorResponse {
        private String errorCode;
        private String message;
    }
    
    // Custom exceptions
    public static class InvalidOrderException extends RuntimeException {
        public InvalidOrderException(String message) {
            super(message);
        }
    }
    
    public static class OrderCreationFailedException extends RuntimeException {
        public OrderCreationFailedException(String message) {
            super(message);
        }
    }
    
    public static class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) {
            super(message);
        }
    }
}
