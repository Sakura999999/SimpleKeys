package com.simplecard.controller;
import com.simplecard.annotation.LogOperation;
import com.simplecard.common.ApiResponse;
import com.simplecard.constant.OrderStatus;
import com.simplecard.repository.OrderRepository;
import com.simplecard.service.InventoryLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/admin/inventory-migration")
@RequiredArgsConstructor
public class AdminInventoryMigrationController {
    private final OrderRepository orders;
    private final com.simplecard.repository.CardKeyRepository cards;
    private final InventoryLifecycleService inventory;
    @PostMapping
    @LogOperation(action="inventory.reconcile", targetType="ORDER", detail="'存量库存预占检查/迁移'")
    public ApiResponse<?> reconcile(@RequestParam(defaultValue="true") boolean dryRun) {
        List<Map<String,Object>> report = new ArrayList<>();
        for (var status : List.of(OrderStatus.PAID, OrderStatus.PENDING)) {
            for (var order : orders.findByStatusOrderByCreatedAtAsc(status)) {
                String action = "CHECK_ONLY";
                if (!dryRun) {
                    if (status == OrderStatus.PAID) { inventory.confirmPayment(order.getId(),null); action="PAYMENT_PROCESSED"; }
                    else {
                        try { inventory.reserve(order.getId()); action="RESERVED"; }
                        catch (com.simplecard.exception.BusinessException e) { inventory.close(order.getId(),true); action="CLOSED_NO_RESERVATION"; }
                    }
                }
                report.add(Map.of("order_id",order.getId(),"previous_status",status,"action",action,"bound_card_count",cards.findByOrderId(order.getId()).size()));
            }
        }
        var orphaned = cards.findOrphanedReservations();
        return ApiResponse.success(Map.of("orders",report,"orphaned_locked_card_ids",orphaned));
    }
}
