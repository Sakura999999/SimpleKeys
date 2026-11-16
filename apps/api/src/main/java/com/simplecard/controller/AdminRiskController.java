package com.simplecard.controller;

import com.simplecard.annotation.LogOperation;
import com.simplecard.common.ApiResponse;
import com.simplecard.service.RiskConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminRiskController {

    private final RiskConfigService riskConfigService;
    private final com.simplecard.config.ClientIpResolver clientIp;
    private final com.simplecard.config.ApiRateLimitProperties limitProperties;
    private final com.simplecard.service.ApiTrafficLimiter limits;

    @GetMapping("/risk/rate-limits")
    public ApiResponse<?> limitInfo() {
        return ApiResponse.success(Map.of("enabled",limitProperties.isEnabled(),"shadow",limitProperties.isShadow(),
                "max_peers",limitProperties.getMaxPeers(),"policies",limitProperties.getPolicies(),
                "allowed",limits.allowed.sum(),"rejected",limits.rejected.sum(),"degraded",limits.degraded.sum(),"shadow_rejected",limits.shadowRejected.sum(),
                "scope_metrics",limits.snapshot()));
    }

    @GetMapping("/risk/network-info")
    public ApiResponse<?> networkInfo(jakarta.servlet.http.HttpServletRequest request) {
        return ApiResponse.success(Map.of("direct_peer",request.getRemoteAddr(),"resolved_client",clientIp.resolve(request),
                "trusted_proxies",clientIp.getTrustedProxies()));
    }

    @GetMapping("/risk-config")
    public ApiResponse<?> getRiskConfig() {
        return ApiResponse.success(riskConfigService.getRiskConfig());
    }

    @LogOperation(action = "config.update", targetType = "RISK_CONFIG", detail = "'更新风控配置'")
    @PutMapping("/risk-config")
    public ApiResponse<Void> updateRiskConfig(@RequestBody Map<String, Object> request) {
        riskConfigService.updateRiskConfig(request);
        return ApiResponse.success();
    }

    @GetMapping("/risk/flagged-orders")
    public ApiResponse<?> getFlaggedOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(riskConfigService.getFlaggedOrders(page, pageSize));
    }
}
