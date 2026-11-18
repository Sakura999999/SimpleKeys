package com.simplecard.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访客订单查单凭证（下称"凭证"）。
 *
 * <p>凭证是邮箱验证码验证通过后签发的不透明随机串，代表<b>「已验证过该邮箱的控制权」</b>，
 * 有效期默认 2 小时。它与订单号凭证的区别：
 * <ul>
 *   <li>订单号（UUIDv4）证明「知道这一笔订单」，只对单笔订单有效</li>
 *   <li>本凭证证明「拥有这个邮箱」，对该邮箱名下的<b>访客订单</b>有效</li>
 * </ul>
 *
 * <p><b>只存哈希：</b>服务端仅保存 {@code sha256(token)}。内存转储不会直接产生可用凭证，
 * 也不能拿来做「哪些邮箱被验证过」的批量提取。
 *
 * <p><b>容量：</b>同邮箱最多并存 {@code maxPerEmail} 张（多设备场景），全站上限
 * {@code maxTotal}。超限时淘汰最旧，绝不无限增长——这是内存实现必须自己守住的边界。
 */
@Slf4j
@Component
public class OrderLookupGrantStore {

    /** 原样持有明文邮箱，用于服务端做订单过滤；对外永不序列化。 */
    public record Grant(String email, long issuedAt, long expiresAt) {
        public boolean expired(long nowMillis) { return nowMillis > expiresAt; }
    }

    /** 签发结果：明文 token 只在此刻返回一次，之后无法再取得。 */
    public record Issued(String token, Grant grant) {}

    private final Map<String, Grant> byTokenHash = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> byEmail = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * 签发新凭证。同一邮箱旧凭证会被逐步淘汰（保留最近 {@code maxPerEmail} 张），
     * 这样「手机验证完再验证平板」不会把手机踢掉，同时内存有界。
     *
     * @return 全站已达上限时返回 empty，调用方应提示稍后重试
     */
    public Optional<Issued> issue(String email, int ttlSeconds, int maxPerEmail, int maxTotal) {
        long now = System.currentTimeMillis();
        purgeExpired(now);

        if (byTokenHash.size() >= maxTotal) {
            // 清理后仍然满，说明确实没有空间，宁可拒绝也不无限分配
            log.warn("Order lookup grant store full ({}), refusing to issue", byTokenHash.size());
            return Optional.empty();
        }

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = sha256(token);

        Grant grant = new Grant(email, now, now + ttlSeconds * 1000L);
        byTokenHash.put(tokenHash, grant);

        byEmail.compute(email, (key, queue) -> {
            Deque<String> q = queue == null ? new ArrayDeque<>() : queue;
            q.addLast(tokenHash);
            while (q.size() > maxPerEmail) {
                String evicted = q.pollFirst();
                if (evicted != null) byTokenHash.remove(evicted);
            }
            return q;
        });

        return Optional.of(new Issued(token, grant));
    }

    /**
     * 解析凭证。token 为 null/空/格式非法时返回 empty，不抛异常——
     * 这个头由客户端自由构造，任何异常都会变成可刷的 500。
     */
    public Optional<Grant> resolve(String token) {
        if (token == null || token.isBlank() || token.length() > 128) return Optional.empty();
        String tokenHash = sha256(token.trim());
        Grant grant = byTokenHash.get(tokenHash);
        if (grant == null) return Optional.empty();
        if (grant.expired(System.currentTimeMillis())) {
            byTokenHash.remove(tokenHash);
            return Optional.empty();
        }
        return Optional.of(grant);
    }

    /** 主动作废（例如用户在页面上点「退出验证」）。 */
    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        byTokenHash.remove(sha256(token.trim()));
    }

    public int size() {
        return byTokenHash.size();
    }

    public void clear() {
        byTokenHash.clear();
        byEmail.clear();
    }

    private void purgeExpired(long now) {
        byTokenHash.entrySet().removeIf(e -> e.getValue().expired(now));
        byEmail.entrySet().removeIf(e -> {
            e.getValue().removeIf(hash -> !byTokenHash.containsKey(hash));
            return e.getValue().isEmpty();
        });
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
