package com.simplecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.common.ApiResponse;
import com.simplecard.constant.ErrorCode;
import com.simplecard.repository.SiteConfigRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(2)
@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final SiteConfigRepository siteConfigRepository;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    /** 通用限流桶 */
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    /** 登录端点独立限流桶（更严格） */
    private final Map<String, TokenBucket> loginBuckets = new ConcurrentHashMap<>();

    // Cache: rate limit config, refresh every 60s
    private volatile int cachedRateLimit = 20;
    private volatile long cacheExpiry = 0;

    /** 登录端点限制：每分钟 5 次 */
    private static final int LOGIN_RATE_PER_MINUTE = 5;

    /** 敏感端点路径 */
    private static final Set<String> LOGIN_PATHS = Set.of(
            "/api/auth/login", "/api/auth/register"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // F21: 仅跳过静态资源（上传文件服务），admin 端点纳入限流（防止暴力攻击）
        if (path.startsWith("/api/uploads")) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientIpResolver.resolve(httpRequest);

        // 登录/注册端点：独立的更严格限流（每分钟 LOGIN_RATE_PER_MINUTE 次）
        if (LOGIN_PATHS.contains(path)) {
            String loginKey = "login:" + clientIp;
            TokenBucket loginBucket = loginBuckets.computeIfAbsent(loginKey,
                    k -> new TokenBucket(LOGIN_RATE_PER_MINUTE, 60_000));
            if (!loginBucket.tryConsume()) {
                rejectTooManyRequests(response, "登录请求过于频繁，请稍后再试");
                return;
            }
        }

        // 通用限流 —— 容量按当次请求读取的配置传入，而非固化在桶创建时。
        // 旧实现把 rateLimit 存进 TokenBucket 的 final capacity，已存在的 IP 桶永远沿用
        // 首次创建时的值：管理员遭遇攻击时把 rate_limit_per_second 调低，正在攻击的 IP
        // 因桶已存在而完全不受影响，收紧限流形同虚设。
        int rateLimit = getRateLimit();
        TokenBucket bucket = buckets.computeIfAbsent(clientIp, k -> new TokenBucket(rateLimit));
        if (!bucket.tryConsume(rateLimit)) {
            rejectTooManyRequests(response, "请求过于频繁，请稍后再试");
            return;
        }

        chain.doFilter(request, response);

        // Periodic cleanup
        long now = System.currentTimeMillis();
        if (buckets.size() > 10000) {
            buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > 60_000);
        }
        if (loginBuckets.size() > 5000) {
            loginBuckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > 120_000);
        }
    }

    private void rejectTooManyRequests(ServletResponse response, String message) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.TOO_MANY_REQUESTS, message)));
    }

    private int getRateLimit() {
        long now = System.currentTimeMillis();
        if (now > cacheExpiry) {
            try {
                cachedRateLimit = siteConfigRepository.findByConfigKey("rate_limit_per_second")
                        .map(c -> {
                            try { return Integer.parseInt(c.getConfigValue()); }
                            catch (Exception e) { return 20; }
                        }).orElse(20);
            } catch (Exception e) {
                // DB unavailable, use cached value
            }
            cacheExpiry = now + 60_000; // 60s TTL
        }
        return cachedRateLimit;
    }

    private static class TokenBucket {
        /** 初始容量，仅用于登录桶等容量固定的场景 */
        private final int defaultCapacity;
        private final long refillIntervalMs;
        private long tokens;
        private long lastRefill;
        volatile long lastAccess;

        /** 默认：每秒补满 */
        TokenBucket(int capacity) {
            this(capacity, 1000);
        }

        /** 自定义补充间隔（毫秒） */
        TokenBucket(int capacity, long refillIntervalMs) {
            this.defaultCapacity = capacity;
            this.refillIntervalMs = refillIntervalMs;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
            this.lastAccess = System.currentTimeMillis();
        }

        /** 使用创建时的容量（登录/注册桶：上限是编译期常量，不随配置变化） */
        synchronized boolean tryConsume() {
            return tryConsume(defaultCapacity);
        }

        /** 使用调用方给出的当前容量，使后台配置变更立即对既有桶生效 */
        synchronized boolean tryConsume(int capacity) {
            refill(capacity);
            lastAccess = System.currentTimeMillis();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        private void refill(int capacity) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            if (elapsed >= refillIntervalMs) {
                long intervals = elapsed / refillIntervalMs;
                long tokensToAdd = intervals * capacity;
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefill = now;
            } else if (tokens > capacity) {
                // 配置下调后立即收敛，不让旧的高容量继续生效到下个补充周期
                tokens = capacity;
            }
        }
    }
}
