package com.simplecard.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class JwtUtils {

    /**
     * 曾硬编码在 application.yml 里的公开默认 secret。它随仓库公开，
     * 任何拿到它的人都能为任意已知 userId 伪造合法令牌，实现免密码账号/管理员接管。
     * 现在一旦检测到该值（或空值），拒绝使用，改为生成随机密钥。
     */
    private static final String KNOWN_PUBLIC_DEFAULT =
            "simplecard-dev-secret-key-must-be-at-least-256-bits-long-for-hs256";

    private final SecretKey key;
    private final long expiration;

    public JwtUtils(@Value("${jwt.secret:}") String secret,
                    @Value("${jwt.expiration}") long expiration) {
        this.key = resolveKey(secret);
        this.expiration = expiration;
    }

    /**
     * 解析签名密钥。
     *
     * <ul>
     *   <li>未配置 / 空 / 等于已知公开默认值 → 生成密码学随机的 HS512 密钥（本次进程有效），
     *       并打印醒目警告。这样本机零配置仍可启动，而生产环境即使漏设 JWT_SECRET，
     *       用的也是不可预测的临时密钥（无法被伪造），且重启即登出、多实例互相拒绝令牌，
     *       会促使运维尽快设置固定 JWT_SECRET。</li>
     *   <li>配置了真实值 → 直接使用。长度不足 HS512 要求时 hmacShaKeyFor 会在启动时抛异常，
     *       等于对弱密钥快速失败。</li>
     * </ul>
     */
    private static SecretKey resolveKey(String secret) {
        if (secret == null || secret.isBlank() || secret.equals(KNOWN_PUBLIC_DEFAULT)) {
            log.warn("""
                    ============================================================
                    JWT_SECRET 未配置（或仍为公开默认值）。已生成临时随机密钥。
                    影响：本次进程有效，服务重启后所有登录失效；多实例部署会互相
                    拒绝对方签发的令牌。生产环境必须显式设置 JWT_SECRET
                    （openssl rand -base64 48），否则登录状态无法持久且无法横向扩展。
                    ============================================================""");
            return Jwts.SIG.HS512.key().build();
        }
        // 真实密钥：长度不足 512 位时 hmacShaKeyFor 抛 WeakKeyException，启动即失败
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UUID userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    /**
     * Parse token safely, returning null if invalid instead of throwing.
     */
    public Claims parseTokenSafe(String token) {
        try {
            return parseToken(token);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isValid(String token) {
        return parseTokenSafe(token) != null;
    }
}
