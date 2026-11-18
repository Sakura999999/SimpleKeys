package com.simplecard.controller;

import com.simplecard.annotation.LogOperation;
import com.simplecard.common.ApiResponse;
import com.simplecard.common.PageResult;
import com.simplecard.entity.UnmatchedTransaction;
import com.simplecard.repository.UnmatchedTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/admin/txid-reviews")
@RequiredArgsConstructor
public class AdminTxidReviewController {

    private final UnmatchedTransactionRepository unmatchedTransactionRepository;
    private final com.simplecard.service.TxidReviewService reviewService;

    @GetMapping
    public ApiResponse<?> listTxidReviews(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {

        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<UnmatchedTransaction> result = (status == null || status.isBlank())
                ? unmatchedTransactionRepository.findAllByOrderByCreatedAtDesc(pageable)
                : unmatchedTransactionRepository.findByStatusOrderByCreatedAtDesc(status, pageable);

        List<Map<String, Object>> items = new ArrayList<>();
        for (UnmatchedTransaction ut : result.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ut.getId());
            item.put("order_id", ut.getOrderId());
            item.put("txid", ut.getTxid());
            item.put("chain", ut.getChain());
            item.put("on_chain_from", ut.getOnChainFrom());
            item.put("on_chain_to", ut.getOnChainTo());
            item.put("on_chain_amount", ut.getOnChainAmount());
            item.put("expected_amount", ut.getExpectedAmount());
            item.put("amount_diff", ut.getAmountDiff());
            item.put("source", ut.getSource());
            item.put("status", ut.getStatus());
            item.put("verify_reason", ut.getVerifyReason());
            item.put("submitted_at", ut.getSubmittedAt());
            item.put("reviewed_at", ut.getReviewedAt());
            items.add(item);
        }

        return ApiResponse.success(PageResult.of(items, page, pageSize, result.getTotalElements()));
    }

    @LogOperation(action = "txid.approve", targetType = "TXID_REVIEW", targetId = "#id", detail = "'通过TXID审核'")
    @PostMapping("/{id}/approve")
    public ApiResponse<Void> approveTxidReview(@PathVariable UUID id) {
        reviewService.approve(id);
        return ApiResponse.success();
    }

    @LogOperation(action = "txid.reject", targetType = "TXID_REVIEW", targetId = "#id", detail = "'拒绝TXID审核'")
    @PostMapping("/{id}/reject")
    public ApiResponse<Void> rejectTxidReview(@PathVariable UUID id, @RequestBody Map<String,String> request) {
        reviewService.reject(id, request.getOrDefault("reason","人工审核拒绝"));
        return ApiResponse.success();
    }
}
