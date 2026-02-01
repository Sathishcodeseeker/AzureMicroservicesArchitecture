package com.enterprise.eventhub.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Order Entity - Business Aggregate
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {
    
    @Id
    private String id;
    
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
    
    @Column(nullable = false, length = 50)
    private String status;
    
    @Column(length = 500)
    private String cancellationReason;
    
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}

package com.enterprise.eventhub.repository;

import com.enterprise.eventhub.domain.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Order Repository
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    
    List<Order> findByCustomerId(String customerId);
    
    List<Order> findByStatus(String status);
}
