package com.enterprise.eventhub.controller;

import com.enterprise.eventhub.cqrs.query.OrderQueryService;
import com.enterprise.eventhub.cqrs.readmodel.OrderReadModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CQRS Query Controller — every read endpoint lives here.
 *
 * This controller depends ONLY on OrderQueryService.
 * It has no path to the write model, the outbox, or CDC.
 *
 * URL namespace is deliberately separate from the command controller:
 *     Commands: POST  /api/v1/orders          (OrderController)
 *     Queries:  GET   /api/v1/orders/query/** (this class)
 */
@RestController
@RequestMapping("/api/v1/orders/query")
@RequiredArgsConstructor
@Slf4j
public class OrderQueryController {

    private final OrderQueryService queryService;

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderReadModel> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(queryService.getOrderById(orderId));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<OrderReadModel>> getOrdersByCustomer(@PathVariable String customerId) {
        return ResponseEntity.ok(queryService.getOrdersByCustomer(customerId));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<OrderReadModel>> getOrdersByStatus(@PathVariable String status) {
        return ResponseEntity.ok(queryService.getOrdersByStatus(status));
    }

    @GetMapping("/status/{status}/count")
    public ResponseEntity<Map<String, Object>> countByStatus(@PathVariable String status) {
        long count = queryService.countOrdersByStatus(status);
        return ResponseEntity.ok(Map.of("status", status, "count", count));
    }

    @GetMapping("/spend")
    public ResponseEntity<Map<String, Double>> getTotalSpend() {
        return ResponseEntity.ok(queryService.getTotalSpendPerCustomer());
    }

    // ---------------------------------------------------------
    @ExceptionHandler(OrderQueryService.OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(OrderQueryService.OrderNotFoundException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }
}
