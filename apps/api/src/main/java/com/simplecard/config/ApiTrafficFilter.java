package com.simplecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.common.ApiResponse;
import com.simplecard.context.RequestContext;
import com.simplecard.exception.BusinessException;
import com.simplecard.service.ApiTrafficLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component @Order(-90) @RequiredArgsConstructor
public class ApiTrafficFilter extends OncePerRequestFilter {
    private final ApiTrafficLimiter limiter;
    private final ClientIpResolver ip;
    private final ObjectMapper json;
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=RequestPaths.application(r),method=r.getMethod();
        if (path.startsWith("/payments/webhook/") || path.startsWith("/uploads")) { chain.doFilter(r,response); return; }
        String scope=null;
        if ("POST".equals(method)) {
            scope=switch(path) {
                case "/auth/login"->"login";case "/auth/register"->"register";
                case "/orders","/orders/from-cart"->"create-order";case "/orders/quote"->"quote";
                case "/orders/query","/orders/deliver"->"query";default->null;
            };
        }
        if (path.startsWith("/cart/") && !"GET".equals(method)) scope="cart";
        if ("GET".equals(method) && (path.equals("/products") || path.equals("/categories"))) scope="catalog";
        // Product detail keeps its original independent 300/30/100 policy, with no extra shared policy.
        if ("GET".equals(method) && path.startsWith("/products/")) { chain.doFilter(r,response);return; }
        if (scope==null) scope=path.startsWith("/orders/")?"order-edge":"misc";
        ApiTrafficLimiter.Permit permit=null;
        try {
            permit=limiter.enterScope(scope);
            if (scope!=null) {
                limiter.check(scope,ip.resolve(r),false);
                if ("create-order".equals(scope) && RequestContext.getUserId()!=null)
                    limiter.check("create-user",RequestContext.getUserId().toString(),false);
            }
            chain.doFilter(r,response);
        } catch (BusinessException e) {
            response.setStatus(e.getHttpStatus().value());response.setContentType("application/json;charset=UTF-8");
            if(response.getStatus()==429 || response.getStatus()==503)response.setHeader("Retry-After","1");
            json.writeValue(response.getWriter(),ApiResponse.error(e.getCode(),e.getMessage()));
        } finally {if(permit!=null)permit.close();}
    }
}
