package com.simplecard.repository;

import com.simplecard.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    @Query("SELECT COALESCE(SUM(oi.quantity), 0) FROM OrderItem oi JOIN Order o ON oi.orderId = o.id " +
            "WHERE oi.productId = :productId AND (o.status = com.simplecard.constant.OrderStatus.PAID OR o.status = com.simplecard.constant.OrderStatus.DELIVERED)")
    int sumQuantityByProductId(@Param("productId") UUID productId);
}
