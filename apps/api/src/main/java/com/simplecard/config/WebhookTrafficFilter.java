package com.simplecard.config;

import com.simplecard.service.LocalRequestWindows;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** Separate, bounded channel protection. Never acknowledges a receipt that has not been persisted. */
@Component @Order(0)
public class WebhookTrafficFilter extends OncePerRequestFilter {
    private final LocalRequestWindows windows=new LocalRequestWindows(8);
    private final Map<String,Semaphore> slots;
    private final int perSecond;
    public WebhookTrafficFilter(@Value("${webhook-guard.per-second:100}") int rate,
                               @Value("${webhook-guard.concurrency:16}") int concurrency) {
        if(rate<1 || concurrency<1)throw new IllegalArgumentException("Invalid webhook limits");
        perSecond=rate;slots=Map.of("epay",new Semaphore(concurrency),"usdt",new Semaphore(concurrency));
    }
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String path=RequestPaths.application(r);
        if(!path.startsWith("/payments/webhook/")) { chain.doFilter(r,response);return; }
        String channel=path.substring("/payments/webhook/".length());Semaphore permits=slots.get(channel);
        if(permits==null) { chain.doFilter(r,response);return; }
        if(!windows.admit(channel,"channel",1000,perSecond,perSecond)) { busy(response);return; }
        if(!permits.tryAcquire()) { busy(response);return; }
        try { chain.doFilter(r,response); } finally { permits.release(); }
    }
    private void busy(HttpServletResponse r) throws IOException {
        r.setStatus(503);r.setHeader("Retry-After","1");r.setContentType("text/plain;charset=UTF-8");r.getWriter().write("retry");
    }
}
