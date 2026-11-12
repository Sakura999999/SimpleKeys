package com.simplecard.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook 端点来源限制过滤器。
 * <p>
 * 支持两种白名单方式（可同时配置，取并集）：
 * <ul>
 *   <li>{@code webhook.allowed-ips} — 静态 IP 白名单（逗号分隔）</li>
 *   <li>{@code webhook.allowed-domains} — 域名白名单（逗号分隔），自动 DNS 解析，5 分钟刷新缓存</li>
 * </ul>
 * 两者均未配置时不启用限制。
 */
@Slf4j
@Component
@Order(1)  // 优先于 RateLimitFilter
@lombok.RequiredArgsConstructor
public class WebhookIpFilter implements Filter {

    private final ClientIpResolver clientIpResolver;

    @Value("${webhook.allowed-ips:}")
    private String allowedIpsConfig;

    @Value("${webhook.allowed-domains:}")
    private String allowedDomainsConfig;

    private volatile Set<String> staticIps;
    private volatile Set<String> resolvedDomainIps;
    private volatile long domainCacheExpiry = 0;

    /** DNS 缓存有效期：5 分钟 */
    private static final long DNS_CACHE_TTL_MS = 300_000;

    private Set<String> getStaticIps() {
        if (staticIps == null) {
            Set<String> set = ConcurrentHashMap.newKeySet();
            if (allowedIpsConfig != null && !allowedIpsConfig.isBlank()) {
                for (String ip : allowedIpsConfig.split(",")) {
                    String trimmed = ip.trim();
                    if (!trimmed.isEmpty()) set.add(trimmed);
                }
            }
            staticIps = set;
        }
        return staticIps;
    }

    private Set<String> getResolvedDomainIps() {
        long now = System.currentTimeMillis();
        if (resolvedDomainIps == null || now > domainCacheExpiry) {
            Set<String> ips = ConcurrentHashMap.newKeySet();
            if (allowedDomainsConfig != null && !allowedDomainsConfig.isBlank()) {
                for (String domain : allowedDomainsConfig.split(",")) {
                    String trimmed = domain.trim();
                    if (trimmed.isEmpty()) continue;
                    try {
                        InetAddress[] addresses = InetAddress.getAllByName(trimmed);
                        for (InetAddress addr : addresses) {
                            ips.add(addr.getHostAddress());
                        }
                    } catch (UnknownHostException e) {
                        log.warn("Webhook allowed domain DNS resolve failed: {}", trimmed);
                    }
                }
            }
            resolvedDomainIps = ips;
            domainCacheExpiry = now + DNS_CACHE_TTL_MS;
            if (!ips.isEmpty()) {
                log.debug("Webhook domain IPs resolved: {}", ips);
            }
        }
        return resolvedDomainIps;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // 仅拦截 webhook 路径
        if (path.startsWith("/api/payments/webhook")) {
            Set<String> ips = getStaticIps();
            Set<String> domainIps = getResolvedDomainIps();
            boolean filterEnabled = !ips.isEmpty() || !domainIps.isEmpty();

            if (filterEnabled) {
                String clientIp = clientIpResolver.resolve(httpRequest);
                if (!ips.contains(clientIp) && !domainIps.contains(clientIp)) {
                    // 降级为仅告警：因网关回调 IP 频繁变动，不再直接拦截。
                    // 安全由 WebhookServiceImpl 的签名校验 + 服务端主动查询双重保障。
                    log.warn("Webhook request from unknown IP: {} not in whitelist, path={} (allowed, relying on signature + query verification)", clientIp, path);
                }
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 启动时立即初始化并打印状态，方便确认配置是否生效
        Set<String> ips = getStaticIps();
        Set<String> domainIps = getResolvedDomainIps();
        if (!ips.isEmpty() || !domainIps.isEmpty()) {
            log.info("Webhook IP filter enabled — static IPs: {}, domains resolved IPs: {}", ips, domainIps);
        } else {
            log.info("Webhook IP filter disabled (no allowed-ips or allowed-domains configured)");
        }
    }

}
