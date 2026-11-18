package com.simplecard.service.impl;

import com.simplecard.config.EmailVerificationProperties;
import com.simplecard.config.MailAvailability;
import com.simplecard.constant.ErrorCode;
import com.simplecard.exception.BusinessException;
import com.simplecard.repository.OrderRepository;
import com.simplecard.service.ApiTrafficLimiter;
import com.simplecard.service.EmailService;
import com.simplecard.service.EmailVerificationCodeStore;
import com.simplecard.service.OrderLookupGrantStore;
import com.simplecard.service.OrderLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 访客订单找回实现。
 *
 * <p>三条不可退让的规则，改动前请先读：
 * <ol>
 *   <li><b>不发信也要占配额。</b>该邮箱没有访客订单时不签发验证码，但仍走一次
 *       {@code reserveAndIssue(null)} 占用发送配额。否则「有配额限制」只对有订单的邮箱成立，
 *       攻击者可以用时间差或配额耗尽与否反推订单存在性。</li>
 *   <li><b>失败原因不对外区分。</b>不存在、已过期、码不对，一律返回
 *       {@link ErrorCode#EMAIL_CODE_INVALID} + 同一句文案。只有「试错超限」单独报，
 *       因为它与订单信息无关，且用户需要知道该重新获取。</li>
 *   <li><b>验证成功不等于能看所有订单。</b>这里只签发凭证；凭证的作用域由
 *       {@link com.simplecard.service.OrderAccessGuard} 限定为「该邮箱 + 访客订单」。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLookupServiceImpl implements OrderLookupService {

    /** 相对保守的 RFC 5322 子集：本地部分不允许多点、域名必须以字母 TLD 结尾 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]{1,64}(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]{1,64})*"
                    + "@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*"
                    + "\\.[A-Za-z]{2,24}$");

    private static final int MAX_EMAIL_LENGTH = 254;

    private final OrderRepository orderRepository;
    private final EmailService emailService;
    private final EmailVerificationCodeStore codeStore;
    private final OrderLookupGrantStore grantStore;
    private final EmailVerificationProperties props;
    private final MailAvailability mailAvailability;
    private final ApiTrafficLimiter trafficLimiter;

    private final SecureRandom random = new SecureRandom();

    @Override
    public boolean isAvailable() {
        return props.isEnabled() && mailAvailability.isAvailable();
    }

    @Override
    public SendResult sendCode(String rawEmail, String clientIp) {
        requireAvailable();
        String email = normalizeEmail(rawEmail);

        // 双维度限流：同一 IP 换邮箱刷、同一邮箱换 IP 刷，都要挡住
        trafficLimiter.check("email-code-send", clientIp, false);
        trafficLimiter.check("email-code-send-email", "email:" + email, false);

        boolean hasGuestOrder = orderRepository.countGuestOrdersByEmail(email) > 0;
        // 无订单时传 null：占配额但不签发验证码，使两条路径对外完全同构
        String plainCode = hasGuestOrder ? generateCode() : null;

        EmailVerificationCodeStore.SendDecision decision = codeStore.reserveAndIssue(
                email, plainCode,
                props.getCodeTtlSeconds(), props.getResendIntervalSeconds(), props.getDailyPerEmail());

        if (!decision.allowed()) {
            if ("DAILY_LIMIT".equals(decision.reason())) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "该邮箱今日发送次数已达上限，请明日再试或联系客服", HttpStatus.TOO_MANY_REQUESTS);
            }
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "请 " + decision.retryAfterSeconds() + " 秒后再试", HttpStatus.TOO_MANY_REQUESTS);
        }

        if (plainCode != null) {
            emailService.sendVerificationCode(email, plainCode, Math.max(1, props.getCodeTtlSeconds() / 60));
            log.info("Order lookup code dispatched to {} (ip={})", maskEmail(email), clientIp);
        } else {
            log.info("Order lookup code withheld for {} (no guest order, ip={})", maskEmail(email), clientIp);
        }

        return new SendResult(props.getResendIntervalSeconds());
    }

    @Override
    public VerifyResult verifyCode(String rawEmail, String code, String clientIp) {
        requireAvailable();
        String email = normalizeEmail(rawEmail);

        String submitted = code == null ? "" : code.trim();
        // 位数不对直接拒绝且不消耗尝试次数：正常用户误输字母不该被扣次数，
        // 而攻击者必须提交合法的 N 位数字才能进入比对，绕不开 maxAttempts
        if (!submitted.matches("\\d{" + props.getCodeLength() + "}")) {
            throw invalidCode();
        }

        trafficLimiter.check("email-code-verify", clientIp, false);
        trafficLimiter.check("email-code-verify-email", "email:" + email, false);

        EmailVerificationCodeStore.ConsumeResult result =
                codeStore.consume(email, submitted, props.getMaxAttempts());

        if (!result.ok()) {
            log.info("Order lookup code rejected for {} (ip={}, outcome={})",
                    maskEmail(email), clientIp, result.outcome());
            if (result.outcome() == EmailVerificationCodeStore.Outcome.TOO_MANY_ATTEMPTS) {
                throw new BusinessException(ErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED,
                        "验证码错误次数过多，请重新获取");
            }
            throw invalidCode();
        }

        Optional<OrderLookupGrantStore.Issued> issued = grantStore.issue(
                email, props.getGrantTtlSeconds(), props.getMaxGrantsPerEmail(), props.getMaxGrants());
        if (issued.isEmpty()) {
            throw new BusinessException(ErrorCode.MAINTENANCE, "服务繁忙，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }

        int guestOrders = (int) orderRepository.countGuestOrdersByEmail(email);
        int registeredOrders = (int) orderRepository.countRegisteredOrdersByEmail(email);

        log.info("Order lookup grant issued to {} (ip={}, guestOrders={}, registeredOrders={})",
                maskEmail(email), clientIp, guestOrders, registeredOrders);

        return new VerifyResult(issued.get().token(), issued.get().grant().expiresAt(),
                guestOrders, registeredOrders);
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_UNAVAILABLE,
                    "邮箱查单功能未开启，请联系客服人工处理");
        }
    }

    /**
     * 规范化邮箱：去空白 + 转小写。
     * 小写化与仓储层 {@code lower(o.email)} 的比较方式保持一致，
     * 否则用户输入大写邮箱时会「验证通过但查不到订单」。
     */
    String normalizeEmail(String rawEmail) {
        if (rawEmail == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "请输入邮箱地址");
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
        }
        return email;
    }

    private String generateCode() {
        int bound = (int) Math.pow(10, props.getCodeLength());
        int value = random.nextInt(bound);
        return String.format("%0" + props.getCodeLength() + "d", value);
    }

    private static BusinessException invalidCode() {
        return new BusinessException(ErrorCode.EMAIL_CODE_INVALID, "验证码错误或已失效");
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String name = email.substring(0, at);
        return (name.length() <= 1 ? "*" : name.charAt(0) + "***") + email.substring(at);
    }
}
