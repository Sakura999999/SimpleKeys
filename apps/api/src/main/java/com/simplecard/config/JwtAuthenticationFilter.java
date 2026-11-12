package com.simplecard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.common.ApiResponse;
import com.simplecard.constant.ErrorCode;
import com.simplecard.context.RequestContext;
import com.simplecard.entity.User;
import com.simplecard.repository.UserRepository;
import com.simplecard.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (StringUtils.hasText(token)) {
                Claims claims = jwtUtils.parseTokenSafe(token);
                if (claims != null) {
                    UUID userId = UUID.fromString(claims.getSubject());
                    String username = claims.get("username", String.class);

                    // 每个携带令牌的请求都以数据库为准复核。
                    //
                    // 旧实现只在 role==ADMIN 且路径以 /api/admin 开头时才查库，其余一律信任
                    // JWT claim，导致：被封禁用户凭旧令牌仍可访问 /user/**、/orders/** 长达 24 小时；
                    // 被降权的前管理员因 /api/upload/** 不匹配该前缀，仍可继续调用（其权限直接来自 claim）；
                    // 且 "/api/admin" 前缀硬编码耦合 context-path，一旦调整就会静默失效。
                    User user = userRepository.findById(userId).orElse(null);
                    if (user == null || user.getIsDeleted() == 1) {
                        log.warn("JWT rejected: userId={} not found or disabled", userId);
                        rejectRequest(response, "身份验证失败，请重新登录");
                        return;
                    }

                    // 登出/改密/封禁后签发分界点之前的令牌一律失效
                    if (isRevoked(claims, user)) {
                        log.info("JWT rejected: issued before revocation cutoff, userId={}", userId);
                        rejectRequest(response, "登录状态已失效，请重新登录");
                        return;
                    }

                    // 角色以数据库为准，不采信 claim —— 降权必须立即生效
                    String role = user.getRole().name();

                    RequestContext.set(new RequestContext.UserInfo(userId, username, role));

                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                    var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * 令牌是否早于该用户的撤销分界点。
     * JWT 的 iat 只有秒级精度，分界点写入时也已截断到秒，两者可直接比较。
     */
    private boolean isRevoked(Claims claims, User user) {
        LocalDateTime cutoff = user.getTokensInvalidBefore();
        if (cutoff == null) return false;

        Date issuedAt = claims.getIssuedAt();
        if (issuedAt == null) return true;   // 无 iat 的令牌无法判定新旧，保守拒绝

        LocalDateTime issued = LocalDateTime.ofInstant(issuedAt.toInstant(), ZoneId.systemDefault());
        return issued.isBefore(cutoff);
    }

    private void rejectRequest(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.UNAUTHORIZED, message)));
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
