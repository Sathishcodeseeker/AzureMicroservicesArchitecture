package com.enterprise.eventhub.cqrs.readmodel;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * CQRS Read Model.
 *
 * Separate entity → separate table → separate lifecycle from the write model.
 * Written ONLY by OrderProjectionService.
 * Read ONLY by OrderQueryService.
 * The command path (OrderService / OrderRepository) never touches this.
 */
@Entity
@Table(name = "order_read_model", indexes = {
    @Index(name = "idx_orm_customer", columnList = "customer_id"),
    @Index(name = "idx_orm_status",   columnList = "last_order_status"),
    @Index(name = "idx_orm_created",  columnList = "created_at")
})
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderReadModel {

    @Id
    private String orderId;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    private Double totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Integer itemCount;

    @Column(nullable = false, length = 500)
    private String shippingAddress;

    /** Latest status — updated on every status-change event. */
    @Column(nullable = false, length = 50)
    private String lastOrderStatus;

    @Column(length = 500)
    private String cancellationReason;

    /** Timestamp from the original OrderCreated event. */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** When this row was last written by the projection. Useful for lag monitoring. */
    @Column(nullable = false)
    private LocalDateTime projectedAt;

    @Column(length = 100)
    private String lastCorrelationId;
}
