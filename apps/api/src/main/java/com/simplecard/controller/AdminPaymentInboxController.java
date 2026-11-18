package com.simplecard.controller;
import com.simplecard.annotation.LogOperation;
import com.simplecard.common.ApiResponse;
import com.simplecard.repository.VerifiedPaymentEventRepository;
import com.simplecard.repository.PaymentAttemptRepository;
import com.simplecard.entity.PaymentAttempt;
import com.simplecard.service.PaymentInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.UUID;
@RestController @RequestMapping("/admin/payment-inbox") @RequiredArgsConstructor
public class AdminPaymentInboxController {
    private final VerifiedPaymentEventRepository events;
    private final PaymentInboxService inbox;
    private final PaymentAttemptRepository attempts;
    private static final int BACKLOG_MINUTES = 5;

    @GetMapping public ApiResponse<?> pending(@RequestParam(defaultValue="1") int page) {
        var result=events.findByProcessedFalseOrderByCreatedAtAsc(PageRequest.of(Math.max(0,page-1),20));
        long overdue = events.countByProcessedFalseAndCreatedAtLessThanEqual(LocalDateTime.now().minusMinutes(BACKLOG_MINUTES));
        var discrepancies = attempts.findUnresolvedPaidChecks(PaymentAttempt.PAID_UNVERIFIED, PageRequest.of(0, 10));
        return ApiResponse.success(java.util.Map.of(
                "list", result.getContent(),
                "total", result.getTotalElements(),
                "overdue_count", overdue,
                "backlog_minutes", BACKLOG_MINUTES,
                "discrepancy_count", discrepancies.getTotalElements(),
                "discrepancies", discrepancies.map(DiscrepancyView::from).getContent()));
    }
    @PostMapping("/{id}/retry")
    @LogOperation(action="payment.retry",targetType="PAYMENT_EVENT",targetId="#id",detail="'重试已核验付款事件'")
    public ApiResponse<?> retry(@PathVariable UUID id) { return ApiResponse.success(inbox.process(id)); }

    @GetMapping("/review")
    public ApiResponse<?> review(@RequestParam(defaultValue = "1") int page) {
        var result = events.findByReviewReasonIsNotNullOrderByCreatedAtDesc(PageRequest.of(Math.max(1, page) - 1, 20));
        return ApiResponse.success(java.util.Map.of("list", result.getContent(), "total", result.getTotalElements()));
    }

    @GetMapping("/attempts")
    public ApiResponse<?> attempts(@RequestParam(defaultValue = "UNKNOWN") PaymentAttempt.State state,
                                   @RequestParam(defaultValue = "1") int page) {
        var result = attempts.findByStateOrderByUpdatedAtDesc(state, PageRequest.of(Math.max(1, page) - 1, 20));
        return ApiResponse.success(java.util.Map.of("list", result.map(AttemptView::from).getContent(),
                "total", result.getTotalElements()));
    }

    private record AttemptView(UUID orderId, String provider, PaymentAttempt.State state, String tradeId,
                               String checkResult, int failures, java.time.LocalDateTime nextCheckAt) {
        private static AttemptView from(PaymentAttempt attempt) {
            return new AttemptView(attempt.getOrderId(), attempt.getProvider(), attempt.getState(),
                    attempt.getGatewayTradeId(), attempt.getLastCheckResult(), attempt.getCheckFailures(), attempt.getNextCheckAt());
        }
    }

    private record DiscrepancyView(UUID orderId, String provider, LocalDateTime nextCheckAt) {
        private static DiscrepancyView from(PaymentAttempt attempt) {
            return new DiscrepancyView(attempt.getOrderId(), attempt.getProvider(), attempt.getNextCheckAt());
        }
    }
}
