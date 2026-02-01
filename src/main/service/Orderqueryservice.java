package com.enterprise.eventhub.cqrs.query;

import com.enterprise.eventhub.cqrs.readmodel.OrderReadModel;
import com.enterprise.eventhub.cqrs.readmodel.OrderReadModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Query Service — reads exclusively from order_read_model.
 *
 * Dependencies this class does NOT have (and must never get):
 *   - OrderRepository       (write-model)
 *   - OutboxEventRepository
 *   - OutboxService / CDC
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderQueryService {

    private final OrderReadModelRepository repo;

    @Transactional(readOnly = true)
    public OrderReadModel getOrderById(String orderId) {
        return repo.findByOrderId(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderReadModel> getOrdersByCustomer(String customerId) {
        return repo.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    @Transactional(readOnly = true)
    public List<OrderReadModel> getOrdersByStatus(String status) {
        return repo.findByLastOrderStatus(status);
    }

    @Transactional(readOnly = true)
    public long countOrdersByStatus(String status) {
        return repo.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public Map<String, Double> getTotalSpendPerCustomer() {
        return repo.findTotalSpendPerCustomer().stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).doubleValue()
                ));
    }

    // ---------------------------------------------------------
    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String msg) { super(msg); }
    }
}
