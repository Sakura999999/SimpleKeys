package com.simplecard.repository;

import com.simplecard.entity.CardImportBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CardImportBatchRepository extends JpaRepository<CardImportBatch, UUID> {

    Page<CardImportBatch> findByProductIdOrderByCreatedAtDesc(UUID productId, Pageable pageable);

    Page<CardImportBatch> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
