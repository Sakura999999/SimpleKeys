package com.simplecard.service.impl;

import com.simplecard.constant.ErrorCode;
import com.simplecard.constant.UserRole;
import com.simplecard.entity.CartItem;
import com.simplecard.entity.User;
import com.simplecard.exception.BusinessException;
import com.simplecard.model.request.LoginRequest;
import com.simplecard.model.request.RegisterRequest;
import com.simplecard.model.response.AuthResponse;
import com.simplecard.model.response.CaptchaResponse;
import com.simplecard.model.response.UserProfileResponse;
import com.simplecard.repository.CartItemRepository;
import com.simplecard.repository.UserRepository;
import com.simplecard.service.AuthService;
import com.simplecard.utils.CaptchaUtils;
import com.simplecard.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final com.simplecard.repository.SiteConfigRepository siteConfigRepository;
    private final CartItemRepository cartItemRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final CaptchaUtils captchaUtils;

    @Override
    public CaptchaResponse generateCaptcha() {
        CaptchaUtils.CaptchaResult result = captchaUtils.generate();
        return new CaptchaResponse(result.captchaId(), result.imageBase64());
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (!captchaUtils.verify(request.getCaptchaId(), request.getCaptcha())) {
            throw new BusinessException(ErrorCode.CAPTCHA_INVALID, "验证码错误或已过期");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "用户名已存在");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "该邮箱已注册");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        userRepository.save(user);

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        return new AuthResponse(token, UserProfileResponse.from(user));
    }

    /** 连续登录失败上限的兜底值 —— 实际以后台 login_attempt_limit 配置为准 */
    private static final int DEFAULT_MAX_FAILED_ATTEMPTS = 5;
    /** 账户锁定时长（分钟） */
    private static final int LOCK_DURATION_MINUTES = 15;

    /**
     * 读取后台配置的登录失败上限。
     *
     * <p>此前该值写死为 5，而 login_attempt_limit 在 data.sql 有种子（10）、在风控 API 白名单里
     * 可写、前端风控页也有输入框 —— 管理员改完会成功落库并回显，验证逻辑却纹丝不动，
     * 是一个"看起来生效、实际无效"的安全控制。
     */
    private int getMaxFailedAttempts() {
        return siteConfigRepository.findByConfigKey("login_attempt_limit")
                .map(c -> {
                    try {
                        int v = Integer.parseInt(c.getConfigValue());
                        return v > 0 ? v : DEFAULT_MAX_FAILED_ATTEMPTS;
                    } catch (Exception e) {
                        return DEFAULT_MAX_FAILED_ATTEMPTS;
                    }
                })
                .orElse(DEFAULT_MAX_FAILED_ATTEMPTS);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(LoginRequest request, String sessionToken) {
        User user = userRepository.findByUsernameOrEmail(request.getAccount(), request.getAccount())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误"));

        if (user.getIsDeleted() == 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "该账号已被禁用");
        }

        // 账户锁定检查
        if (user.getLockUntil() != null) {
            if (user.getLockUntil().isAfter(java.time.LocalDateTime.now())) {
                long remainMinutes = java.time.Duration.between(java.time.LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "账号已被锁定，请 " + remainMinutes + " 分钟后再试");
            }
            // 锁定已过期：重置失败计数，给予完整的重试机会
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            int maxFailedAttempts = getMaxFailedAttempts();
            // 记录失败次数
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            if (user.getFailedLoginAttempts() >= maxFailedAttempts) {
                user.setLockUntil(java.time.LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                userRepository.save(user);
                log.warn("Account locked due to {} failed login attempts: {}", maxFailedAttempts, user.getUsername());
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED,
                        "连续登录失败 " + maxFailedAttempts + " 次，账号已锁定 " + LOCK_DURATION_MINUTES + " 分钟");
            }
            userRepository.save(user);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "用户名或密码错误");
        }

        // 登录成功：重置失败计数和锁定状态
        if (user.getFailedLoginAttempts() > 0 || user.getLockUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockUntil(null);
            userRepository.save(user);
        }

        // Merge guest cart on login
        if (StringUtils.hasText(sessionToken)) {
            mergeCart(sessionToken, user.getId());
        }

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole().name());
        return new AuthResponse(token, UserProfileResponse.from(user));
    }

    @Override
    @Transactional
    public void logout() {
        // 旧实现只写一行日志 —— 无状态 JWT 不会因此失效，令牌在 24 小时内仍可继续使用。
        // 现在推进撤销分界点，使本次登出前签发的所有令牌立即作废（见 User.tokensInvalidBefore）。
        java.util.UUID userId = com.simplecard.context.RequestContext.getUserId();
        if (userId == null) return;
        userRepository.findById(userId).ifPresent(user -> {
            user.setTokensInvalidBefore(
                    java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            userRepository.save(user);
            log.info("User {} logged out, tokens issued before now are revoked", user.getUsername());
        });
    }

    private void mergeCart(String sessionToken, java.util.UUID userId) {
        List<CartItem> guestItems = cartItemRepository.findBySessionToken(sessionToken);
        for (CartItem guestItem : guestItems) {
            Optional<CartItem> existing = cartItemRepository
                    .findByUserIdAndProductIdAndSpecId(userId, guestItem.getProductId(), guestItem.getSpecId());
            if (existing.isPresent()) {
                // Merge quantity into user's existing item, then delete the guest item
                existing.get().setQuantity(existing.get().getQuantity() + guestItem.getQuantity());
                cartItemRepository.save(existing.get());
                cartItemRepository.delete(guestItem);
            } else {
                // Reassign guest item to user
                guestItem.setUserId(userId);
                guestItem.setSessionToken(null);
                cartItemRepository.save(guestItem);
            }
        }
    }
}
