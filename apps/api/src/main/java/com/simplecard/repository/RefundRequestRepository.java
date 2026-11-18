package com.simplecard.repository;
import com.simplecard.entity.RefundRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface RefundRequestRepository extends JpaRepository<RefundRequest, UUID> {
    Optional<RefundRequest> findByOrderId(UUID orderId);
    boolean existsByOrderId(UUID orderId);
}
