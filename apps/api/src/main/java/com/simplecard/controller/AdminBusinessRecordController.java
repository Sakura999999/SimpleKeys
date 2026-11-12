package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.entity.BusinessRecord;
import com.simplecard.repository.BusinessRecordRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@RestController @RequestMapping("/admin/business-records") @RequiredArgsConstructor
public class AdminBusinessRecordController {
    private final BusinessRecordRepository repository;

    public record Input(
        @NotBlank @Size(max=120) String customerName,
        @NotBlank @Size(max=255) String purchaseContent,
        @Size(max=120) String bd, @Size(max=120) String channel,
        @Min(1) @Max(1000000) int quantity,
        @DecimalMin("0.00") @Digits(integer=14, fraction=2) BigDecimal unitPriceUsd,
        @DecimalMin("0.00") @Digits(integer=14, fraction=2) BigDecimal unitPriceCny,
        @DecimalMin("0.00") @Digits(integer=14, fraction=2) BigDecimal unitCostUsd,
        boolean received, LocalDateTime orderedAt, LocalDateTime receivedAt,
        @Size(max=2000) String notes) {}

    @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String query,
        @RequestParam(required=false) String bd, @RequestParam(required=false) Boolean received,
        @RequestParam(required=false) String month) {
        Specification<BusinessRecord> spec = (root, q, cb) -> cb.conjunction();
        if (query != null && !query.isBlank()) {
            String term = "%" + query.trim().toLowerCase() + "%";
            spec = spec.and((root,q,cb) -> cb.or(cb.like(cb.lower(root.get("customerName")),term), cb.like(cb.lower(root.get("purchaseContent")),term), cb.like(cb.lower(root.get("channel")),term)));
        }
        if (bd != null && !bd.isBlank()) { String term = "%" + bd.trim().toLowerCase() + "%"; spec = spec.and((root,q,cb) -> cb.like(cb.lower(root.get("bd")),term)); }
        if (received != null) spec = spec.and((root,q,cb) -> cb.equal(root.get("received"),received));
        if (month != null && !month.isBlank()) {
            final YearMonth ym;
            try { ym = YearMonth.parse(month); } catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"月份格式应为 YYYY-MM"); }
            spec = spec.and((root,q,cb) -> cb.and(cb.greaterThanOrEqualTo(root.get("orderedAt"),ym.atDay(1).atStartOfDay()), cb.lessThan(root.get("orderedAt"),ym.plusMonths(1).atDay(1).atStartOfDay())));
        }
        return ApiResponse.success(repository.findAll(spec, PageRequest.of(Math.max(page,0), Math.min(Math.max(size,1),100), Sort.by(Sort.Direction.DESC,"createdAt"))));
    }
    @PostMapping public ApiResponse<BusinessRecord> create(@Valid @RequestBody Input input) { return ApiResponse.success(repository.save(apply(new BusinessRecord(),input))); }
    @PutMapping("/{id}") public ApiResponse<BusinessRecord> update(@PathVariable UUID id, @Valid @RequestBody Input input) {
        BusinessRecord record = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"业务记录不存在"));
        return ApiResponse.success(repository.save(apply(record,input)));
    }
    private BusinessRecord apply(BusinessRecord r, Input i) {
        r.setCustomerName(i.customerName().trim()); r.setPurchaseContent(i.purchaseContent().trim()); r.setBd(i.bd()); r.setChannel(i.channel()); r.setQuantity(i.quantity());
        r.setUnitPriceUsd(i.unitPriceUsd()); r.setUnitPriceCny(i.unitPriceCny()); r.setUnitCostUsd(i.unitCostUsd()); r.setReceived(i.received());
        r.setOrderedAt(i.orderedAt()); r.setReceivedAt(i.receivedAt()); r.setNotes(i.notes()); return r;
    }
}
