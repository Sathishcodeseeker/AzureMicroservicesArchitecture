package com.enterprise.eventhub.cqrs.readmodel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderReadModelRepository extends JpaRepository<OrderReadModel, String> {

    Optional<OrderReadModel> findByOrderId(String orderId);

    List<OrderReadModel> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<OrderReadModel> findByLastOrderStatus(String status);

    @Query("SELECT COUNT(o) FROM OrderReadModel o WHERE o.lastOrderStatus = :status")
    long countByStatus(@Param("status") String status);

    /** Total spend per customer — used by the /spend dashboard endpoint. */
    @Query("SELECT o.customerId, SUM(o.totalAmount) FROM OrderReadModel o GROUP BY o.customerId")
    List<Object[]> findTotalSpendPerCustomer();
}
