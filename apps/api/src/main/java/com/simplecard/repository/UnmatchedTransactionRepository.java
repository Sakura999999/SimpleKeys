package com.simplecard.repository;

import com.simplecard.entity.UnmatchedTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnmatchedTransactionRepository extends JpaRepository<UnmatchedTransaction, UUID> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from UnmatchedTransaction r where r.id = :id")
    Optional<UnmatchedTransaction> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<UnmatchedTransaction> findByTxid(String txid);

    Page<UnmatchedTransaction> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<UnmatchedTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<UnmatchedTransaction> findByOrderId(UUID orderId);
}
