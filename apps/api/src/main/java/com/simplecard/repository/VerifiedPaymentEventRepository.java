package com.simplecard.repository;
import com.simplecard.entity.VerifiedPaymentEvent;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.*;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.*;
public interface VerifiedPaymentEventRepository extends JpaRepository<VerifiedPaymentEvent,UUID> {
    Optional<VerifiedPaymentEvent> findByEventKey(String key);
    boolean existsByOrderIdAndProcessedFalse(UUID orderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from VerifiedPaymentEvent e where e.id=:id")
    Optional<VerifiedPaymentEvent> lockEvent(@Param("id") UUID id);
    List<VerifiedPaymentEvent> findByProcessedFalseAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(LocalDateTime now, Pageable page);
    Page<VerifiedPaymentEvent> findByProcessedFalseOrderByCreatedAtAsc(Pageable page);
}
