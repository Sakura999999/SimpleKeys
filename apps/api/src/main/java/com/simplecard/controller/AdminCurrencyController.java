package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.CurrencyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Map;

@RestController @RequestMapping("/admin/currencies") @RequiredArgsConstructor
public class AdminCurrencyController {
    private final CurrencyRepository repository;

    @GetMapping public ApiResponse<?> list() {
        return ApiResponse.success(repository.findAll().stream()
                .sorted(java.util.Comparator.comparing(com.simplecard.entity.Currency::getCode))
                .map(c -> {
                    Map<String,Object> m = new java.util.LinkedHashMap<>();
                    m.put("code",c.getCode()); m.put("name",c.getName());
                    m.put("rate_to_cny", "CNY".equals(c.getCode()) ? BigDecimal.ONE : c.getRateToCny());
                    m.put("updated_at",c.getUpdatedAt()); return m;
                }).toList());
    }

    @PutMapping("/exchange-rates") @Transactional
    public ApiResponse<Void> save(@RequestBody Map<String,String> rates) {
        for (var entry : rates.entrySet()) {
            var currency = repository.findAll().stream().filter(c -> c.getCode().equals(entry.getKey()))
                    .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,"未知币种"));
            String raw = entry.getValue();
            BigDecimal rate = null;
            if (raw != null && !raw.isBlank()) {
                try { rate = new BigDecimal(raw); } catch (RuntimeException ex) { throw new BusinessException(ErrorCode.BAD_REQUEST,"汇率必须是有效数字"); }
                if (rate.signum() <= 0 || rate.compareTo(new BigDecimal("1000000")) > 0 || rate.scale() > 8)
                    throw new BusinessException(ErrorCode.BAD_REQUEST,"汇率须大于 0、不超过 1000000，最多 8 位小数");
            }
            if ("CNY".equals(currency.getCode()) && (rate == null || rate.compareTo(BigDecimal.ONE) != 0))
                throw new BusinessException(ErrorCode.BAD_REQUEST,"人民币基准汇率必须为 1");
            currency.setRateToCny(rate); repository.save(currency);
        }
        return ApiResponse.success();
    }
}
