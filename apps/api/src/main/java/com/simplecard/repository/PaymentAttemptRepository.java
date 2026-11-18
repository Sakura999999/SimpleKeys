package com.simplecard.repository;

import com.simplecard.entity.PaymentAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    Optional<PaymentAttempt> findByOrderId(UUID orderId);
    Page<PaymentAttempt> findByStateOrderByUpdatedAtDesc(PaymentAttempt.State state, Pageable page);

    @Query("""
            select a from PaymentAttempt a, Order o
            where a.orderId = o.id and a.lastCheckResult = :result
              and o.paidAt is null
              and o.status in (com.simplecard.constant.OrderStatus.PENDING,
                              com.simplecard.constant.OrderStatus.EXPIRED,
                              com.simplecard.constant.OrderStatus.CANCELLED)
              and not exists (select e.id from VerifiedPaymentEvent e where e.orderId = o.id and e.processed = false)
            order by a.createdAt, a.id
            """)
    Page<PaymentAttempt> findUnresolvedPaidChecks(String result, Pageable page);

    @Transactional
    @Modifying
    @Query("""
            update PaymentAttempt a set a.pendingTxId = :txid
            where a.orderId = :orderId and a.provider = 'usdt'
              and (a.pendingTxId is null or a.pendingTxId = :txid)
            """)
    int rememberSignedTransfer(UUID orderId, String txid);

    @Query("select a.id from PaymentAttempt a where a.nextCheckAt <= :now order by a.nextCheckAt, a.id")
    List<UUID> findDue(LocalDateTime now, Pageable page);

    @Transactional
    @Modifying
    @Query("update PaymentAttempt a set a.nextCheckAt = :lease where a.id = :id and a.nextCheckAt <= :now")
    int claim(UUID id, LocalDateTime now, LocalDateTime lease);

    @Transactional
    @Modifying
    @Query("""
            update PaymentAttempt a set a.nextCheckAt = :next, a.state = :state,
                   a.checkFailures = :failures, a.lastCheckResult = :result
            where a.id = :id and a.nextCheckAt = :lease
            """)
    int completeCheck(UUID id, LocalDateTime lease, LocalDateTime next, PaymentAttempt.State state, int failures, String result);
}
