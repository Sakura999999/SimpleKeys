package com.simplecard.service;

import com.simplecard.constant.ErrorCode;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.*;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j @Service @RequiredArgsConstructor
public class TxidReviewService {
    private final UnmatchedTransactionRepository reviews;
    private final OrderRepository orders;
    private final InventoryLifecycleService inventory;
    private final EntityManager entityManager;

    // Lock order: review -> order -> cards. Redis coordination wraps this entire transaction.
    @Transactional(timeout=15)
    public void approve(UUID id) {
        UnmatchedTransaction review = pending(id);
        if (review.getOrderId() == null)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"审核记录未关联订单，请先人工核查");
        var existing = orders.findByUsdtTxId(review.getTxid());
        if (existing.isPresent() && !existing.get().getId().equals(review.getOrderId()))
            throw new BusinessException(ErrorCode.TXID_ALREADY_USED,"该 TXID 已关联其他订单");
        Order result = inventory.confirmReviewedPayment(review.getOrderId(),review.getTxid());
        review.setStatus("APPROVED"); review.setVerifyReason("人工审核通过");
        reviewed(review);
        log.info("TXID review approved: review={}, order={}, resultingStatus={}",id,result.getId(),result.getStatus());
    }

    @Transactional(timeout=15)
    public void reject(UUID id, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"请填写拒绝原因（最多500字）");
        UnmatchedTransaction review = pending(id);
        review.setStatus("REJECTED"); review.setVerifyReason(reason.trim()); reviewed(review);
        log.info("TXID review rejected: review={}, order={}",id,review.getOrderId());
    }

    private UnmatchedTransaction pending(UUID id) {
        if (entityManager.getEntityManagerFactory().getProperties().getOrDefault("hibernate.dialect", "").toString().contains("PostgreSQL"))
            entityManager.createNativeQuery("set local lock_timeout = '5s'").executeUpdate();
        UnmatchedTransaction review = reviews.findByIdForUpdate(id).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND,"审核记录不存在"));
        entityManager.refresh(review,LockModeType.PESSIMISTIC_WRITE);
        if (!"PENDING_REVIEW".equals(review.getStatus()))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"该记录状态不允许审核");
        return review;
    }
    private void reviewed(UnmatchedTransaction review) {
        review.setReviewerId(RequestContext.getUserId()); review.setReviewedAt(LocalDateTime.now());
        reviews.saveAndFlush(review);
    }
}
