package com.simplecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.common.ApiResponse;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.service.ProductQueryProtection;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(0)
@RequiredArgsConstructor
public class ProductGuardFilter extends OncePerRequestFilter {
    private final ProductQueryProtection protection;
    private final ClientIpResolver ip;
    private final ObjectMapper json;
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        String path = r.getRequestURI().substring(r.getContextPath().length());
        return !protection.enabled() || !"GET".equals(r.getMethod()) || !path.startsWith("/products/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean entered = false;
        try {
            entered = protection.enterRequest();
            if (!entered) throw ProductQueryProtection.busy();
            protection.admit(ip.resolve(request));
            String id = request.getRequestURI().substring(request.getContextPath().length() + "/products/".length());
            if (!id.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new BusinessException(ErrorCode.BAD_REQUEST, "商品 ID 格式错误");
            chain.doFilter(request, response);
        } catch (BusinessException e) {
            response.setStatus(e.getHttpStatus().value());
            response.setContentType("application/json;charset=UTF-8");
            if (response.getStatus() == 429 || response.getStatus() == 503) response.setHeader("Retry-After", "1");
            json.writeValue(response.getWriter(), ApiResponse.error(e.getCode(), e.getMessage()));
        } finally { if (entered) protection.exitRequest(); }
    }
}
