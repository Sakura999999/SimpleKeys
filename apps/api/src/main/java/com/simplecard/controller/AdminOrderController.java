package com.simplecard.controller;

import com.simplecard.annotation.LogOperation;
import com.simplecard.common.ApiResponse;
import com.simplecard.service.AdminOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;
    private final com.simplecard.service.InventoryLifecycleService inventoryLifecycle;

    @GetMapping
    public ApiResponse<?> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(value = "order_type", required = false) String orderType,
            @RequestParam(value = "payment_method", required = false) String paymentMethod,
            @RequestParam(value = "is_risk_flagged", required = false) Boolean isRiskFlagged,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(adminOrderService.listOrders(status, orderType, paymentMethod,
                isRiskFlagged, keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getOrderDetail(@PathVariable UUID id) {
        return ApiResponse.success(adminOrderService.getOrderDetail(id));
    }

    @LogOperation(action = "order.mark_paid", targetType = "ORDER", targetId = "#id", detail = "'手动标记已付'")
    @PostMapping("/{id}/mark-paid")
    public ApiResponse<Void> markPaid(@PathVariable UUID id) {
        adminOrderService.markPaid(id);
        return ApiResponse.success();
    }
    @LogOperation(action = "order.retry_delivery", targetType = "ORDER", targetId = "#id", detail = "'重试发货'")
    @PostMapping("/{id}/retry-delivery")
    public ApiResponse<Void> retryDelivery(@PathVariable UUID id) {
        inventoryLifecycle.retryDelivery(id);
        return ApiResponse.success();
    }

    @LogOperation(action = "order.refund_record", targetType = "ORDER", targetId = "#id", detail = "'登记人工退款'")
    @PostMapping("/{id}/refund-record")
    public ApiResponse<Void> refund(@PathVariable UUID id, @RequestBody java.util.Map<String,String> body) {
        inventoryLifecycle.recordRefund(id, body.get("reference"));
        return ApiResponse.success();
    }
}
