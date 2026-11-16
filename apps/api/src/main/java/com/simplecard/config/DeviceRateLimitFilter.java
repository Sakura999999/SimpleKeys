package com.simplecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.common.ApiResponse;
import com.simplecard.constant.ErrorCode;
import com.simplecard.repository.SiteConfigRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local bounded fixed-window device quotas. Redis request policies are handled separately.
 * Device IDs are caller-controlled hints, never ownership credentials. Seed and fallback default: enabled.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class DeviceRateLimitFilter implements Filter {

    private final SiteConfigRepository siteConfigRepository;
    private final ObjectMapper objectMapper;
    private final ClientIpResolver clientIpResolver;

    private static final String DEVICE_ID_HEADER = "X-Device-Id";

    /** 合法 deviceId 格式：64 位十六进制（SHA-256 输出） */
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private static final Pattern TXID_PATH_PATTERN = Pattern.compile("^/orders/([^/]+)/txid-verify$");

    // ── 限流桶存储 ──
    private final Map<String, FixedWindowCounter> deviceBuckets = new ConcurrentHashMap<>();

    // ── 配置缓存 ──
    private volatile boolean cachedEnabled = true;
    private volatile int orderLimit = 15;
    private volatile int txidDeviceLimit = 5;
    private volatile int queryLimit = 50;
    private volatile int loginLimit = 10;
    private volatile int registerLimit = 10;
    private volatile long configCacheExpiry = 0;

    /** 限流类别 */
    private enum Category {
        ORDER, TXID, QUERY, LOGIN, REGISTER, NONE
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = RequestPaths.application(httpRequest);
        String method = httpRequest.getMethod();

        Category category = classify(path, method);
        if (category == Category.NONE) {
            chain.doFilter(request, response);
            return;
        }

        // 检查开关
        refreshConfig();
        if (!cachedEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // 解析限流 key（优先 deviceId，降级为 IP）
        String rateLimitKey = resolveRateLimitKey(httpRequest);

        int limit = switch (category) {
            case ORDER -> orderLimit;
            case TXID -> txidDeviceLimit;
            case QUERY -> queryLimit;
            case LOGIN -> loginLimit;
            case REGISTER -> registerLimit;
            default -> 20;
        };

        String bucketKey = "device:" + rateLimitKey + ":" + category.name();
        FixedWindowCounter counter=com.simplecard.service.BoundedBuckets.get(deviceBuckets,bucketKey,10000,
                () -> new FixedWindowCounter(3600000L),old -> System.nanoTime()/1000000L-old.lastAccess>3600000L);
        if (counter==null || !counter.tryIncrement(limit)) {
            log.debug("Device rate limit hit: category={}, key={}, path={}", category, rateLimitKey, path);
            reject(response);
            return;
        }

        chain.doFilter(request, response);

        // 定期清理
        long now = System.nanoTime() / 1_000_000L;
        if (deviceBuckets.size() > 10000) {
            deviceBuckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > 3600_000L);
        }

    }

    private Category classify(String path, String method) {
        if (!"POST".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)) {
            return Category.NONE;
        }

        // POST 精确匹配
        if ("POST".equalsIgnoreCase(method)) {
            if ("/orders".equals(path) || "/orders/from-cart".equals(path)) {
                return Category.ORDER;
            }
            if ("/orders/query".equals(path) || "/orders/deliver".equals(path)) {
                return Category.QUERY;
            }
            if ("/auth/login".equals(path)) {
                return Category.LOGIN;
            }
            if ("/auth/register".equals(path)) {
                return Category.REGISTER;
            }
            // POST 动态路径
            if (TXID_PATH_PATTERN.matcher(path).matches()) {
                return Category.TXID;
            }
        }

        // GET 动态路径：export
        if ("GET".equalsIgnoreCase(method) && path.matches("^/orders/[^/]+/export$")) {
            return Category.QUERY;
        }

        return Category.NONE;
    }

    private String resolveRateLimitKey(HttpServletRequest request) {
        String deviceId = request.getHeader(DEVICE_ID_HEADER);
        if (StringUtils.hasText(deviceId) && DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
            return deviceId;
        }
        // 降级为 IP
        return clientIpResolver.resolve(request);
    }

    private void refreshConfig() {
        long now = System.nanoTime() / 1_000_000L;
        if (now > configCacheExpiry) {
            try {
                cachedEnabled = getConfigBool("device_rate_limit_enabled", true);
                orderLimit = getConfigInt("device_order_limit_per_hour", 15);
                txidDeviceLimit = getConfigInt("device_txid_limit_per_hour", 5);
                queryLimit = getConfigInt("device_query_limit_per_hour", 50);
                loginLimit = getConfigInt("device_login_limit_per_hour", 10);
                registerLimit = getConfigInt("device_register_limit_per_hour", 10);
            } catch (Exception e) {
                // DB 不可用时使用缓存值
            }
            configCacheExpiry = now + 60_000;
        }
    }

    private int getConfigInt(String key, int defaultValue) {
        return siteConfigRepository.findByConfigKey(key)
                .map(c -> {
                    try { int value=Integer.parseInt(c.getConfigValue());return value>0 && value<=10000?value:defaultValue; }
                    catch (Exception e) { return defaultValue; }
                }).orElse(defaultValue);
    }

    private boolean getConfigBool(String key, boolean defaultValue) {
        return siteConfigRepository.findByConfigKey(key)
                .map(c -> !"false".equalsIgnoreCase(c.getConfigValue()))
                .orElse(defaultValue);
    }

    private void reject(ServletResponse response) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.DEVICE_RATE_LIMITED, "操作过于频繁，请稍后再试")));
    }

    @Override
    public void init(FilterConfig filterConfig) {
        log.info("Device rate limit filter initialized");
    }

    /**
     * 滑动窗口计数器。
     * 在 windowMs 时间窗口内计数请求次数，超过 limit 则拒绝。
     */
    private static class FixedWindowCounter {
        private final long windowMs;
        private long windowStart;
        private int count;
        volatile long lastAccess;

        FixedWindowCounter(long windowMs) {
            this.windowMs = windowMs;
            this.windowStart = System.nanoTime() / 1_000_000L;
            this.count = 0;
            this.lastAccess = System.nanoTime() / 1_000_000L;
        }

        synchronized boolean tryIncrement(int limit) {
            long now = System.nanoTime() / 1_000_000L;
            lastAccess = now;

            // 窗口已过期，重置
            if (now - windowStart >= windowMs) {
                windowStart = now;
                count = 0;
            }

            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }
}
