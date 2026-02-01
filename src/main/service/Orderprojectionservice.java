package com.enterprise.eventhub.cqrs.projection;

import com.enterprise.eventhub.cqrs.readmodel.OrderReadModel;
import com.enterprise.eventhub.cqrs.readmodel.OrderReadModelRepository;
import com.enterprise.eventhub.domain.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Projection Service — rebuilds the read model from domain events.
 *
 * Contract:
 *   1. Each method handles exactly one event type.
 *   2. Each method is idempotent: applying the same event twice leaves the row unchanged.
 *   3. Out-of-order events (e.g. StatusChanged before Created) are detected and
 *      skipped — the consumer will NOT checkpoint, so Event Hub redelivers later.
 *   4. This class has zero dependency on the write model (Order / OrderRepository).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderProjectionService {

    private final OrderReadModelRepository repo;

    /**
     * OrderCreated → INSERT (or idempotent re-INSERT if replayed).
     */
    @Transactional
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Projecting OrderCreated: orderId={}", event.getOrderId());

        OrderReadModel model = OrderReadModel.builder()
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .totalAmount(event.getTotalAmount())
                .currency(event.getCurrency())
                .itemCount(event.getItemCount())
                .shippingAddress(event.getShippingAddress())
                .lastOrderStatus(event.getOrderStatus())
                .createdAt(event.getOccurredAt() != null ? event.getOccurredAt() : LocalDateTime.now())
                .projectedAt(LocalDateTime.now())
                .lastCorrelationId(event.getCorrelationId() != null ? event.getCorrelationId().toString() : null)
                .build();

        // JPA merge: inserts if absent, overwrites if present (idempotent).
        repo.save(model);
        log.info("OrderCreated projected: orderId={}", event.getOrderId());
    }

    /**
     * OrderStatusChanged → UPDATE lastOrderStatus on existing row.
     *
     * Returns false if the row does not yet exist (out-of-order delivery).
     * The caller (EventHandler) must NOT checkpoint in that case so the
     * event is redelivered after the OrderCreated event catches up.
     */
    @Transactional
    public boolean onOrderStatusChanged(String orderId, String newStatus, String correlationId) {
        log.info("Projecting OrderStatusChanged: orderId={}, newStatus={}", orderId, newStatus);

        OrderReadModel existing = repo.findByOrderId(orderId).orElse(null);
        if (existing == null) {
            log.warn("OrderStatusChanged for unknown orderId={} — skipping (will be redelivered)", orderId);
            return false;   // signal: do not checkpoint
        }

        existing.setLastOrderStatus(newStatus);
        existing.setProjectedAt(LocalDateTime.now());
        if (correlationId != null) existing.setLastCorrelationId(correlationId);

        repo.save(existing);
        log.info("OrderStatusChanged projected: orderId={}", orderId);
        return true;
    }

    /**
     * OrderCancelled → set status + reason.
     * Same out-of-order guard as onOrderStatusChanged.
     */
    @Transactional
    public boolean onOrderCancelled(String orderId, String reason, String correlationId) {
        log.info("Projecting OrderCancelled: orderId={}", orderId);

        OrderReadModel existing = repo.findByOrderId(orderId).orElse(null);
        if (existing == null) {
            log.warn("OrderCancelled for unknown orderId={} — skipping", orderId);
            return false;
        }

        existing.setLastOrderStatus("CANCELLED");
        existing.setCancellationReason(reason);
        existing.setProjectedAt(LocalDateTime.now());
        if (correlationId != null) existing.setLastCorrelationId(correlationId);

        repo.save(existing);
        log.info("OrderCancelled projected: orderId={}", orderId);
        return true;
    }
}
