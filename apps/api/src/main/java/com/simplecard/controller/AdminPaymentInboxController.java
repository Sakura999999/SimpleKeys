package com.simplecard.controller;
import com.simplecard.annotation.LogOperation;
import com.simplecard.common.ApiResponse;
import com.simplecard.repository.VerifiedPaymentEventRepository;
import com.simplecard.service.PaymentInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/admin/payment-inbox") @RequiredArgsConstructor
public class AdminPaymentInboxController {
    private final VerifiedPaymentEventRepository events;
    private final PaymentInboxService inbox;
    @GetMapping public ApiResponse<?> pending(@RequestParam(defaultValue="1") int page) {
        var result=events.findByProcessedFalseOrderByCreatedAtAsc(PageRequest.of(Math.max(0,page-1),20));
        return ApiResponse.success(java.util.Map.of("list",result.getContent(),"total",result.getTotalElements()));
    }
    @PostMapping("/{id}/retry")
    @LogOperation(action="payment.retry",targetType="PAYMENT_EVENT",targetId="#id",detail="'重试已核验付款事件'")
    public ApiResponse<?> retry(@PathVariable UUID id) { return ApiResponse.success(inbox.process(id)); }
}
