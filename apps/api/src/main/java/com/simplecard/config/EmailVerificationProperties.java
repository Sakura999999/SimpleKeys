package com.simplecard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮箱验证码查单的参数。全部可经环境变量覆盖，便于按实际垃圾邮件压力调整。
 *
 * <p>与 {@code mail.enabled} 是两层开关：
 * {@code mail.enabled} 决定「能不能发信」，本类的 {@code enabled} 决定
 * 「即使能发信，是否开放这个入口」。运营可以临时关掉查单而不影响发货通知。
 */
@Data
@Component
@ConfigurationProperties("mail.verification")
public class EmailVerificationProperties {

    /** 查单入口总开关（仍需 mail.enabled 且 SMTP 已配置才真正可用） */
    private boolean enabled = true;

    /** 验证码位数 */
    private int codeLength = 6;

    /** 验证码有效期（秒） */
    private int codeTtlSeconds = 600;

    /** 同一邮箱两次发送的最小间隔（秒） */
    private int resendIntervalSeconds = 60;

    /** 单个验证码最多允许的校验失败次数，超过即销毁 */
    private int maxAttempts = 5;

    /** 查单凭证有效期（秒），默认 2 小时 */
    private int grantTtlSeconds = 7200;

    /** 同一邮箱每天最多发送次数 */
    private int dailyPerEmail = 10;

    /** 同一邮箱最多并存的凭证数（多设备场景），超出淘汰最旧 */
    private int maxGrantsPerEmail = 3;

    /** 全站凭证上限，防止内存被刷爆 */
    private int maxGrants = 10000;

    /** 单次查询最多返回的订单数 */
    private int maxOrdersPerLookup = 100;

    /**
     * 参数自检。范围错误会让安全控制悄悄失效（例如 codeLength=4 不足以抵抗爆破），
     * 因此在启动时就拒绝，而不是运行到一半才发现。
     */
    @jakarta.annotation.PostConstruct
    void validate() {
        require(codeLength >= 6 && codeLength <= 10, "mail.verification.code-length 必须在 6~10");
        require(codeTtlSeconds >= 60 && codeTtlSeconds <= 3600, "mail.verification.code-ttl-seconds 必须在 60~3600");
        require(resendIntervalSeconds >= 30 && resendIntervalSeconds <= 3600,
                "mail.verification.resend-interval-seconds 必须在 30~3600");
        require(maxAttempts >= 3 && maxAttempts <= 10, "mail.verification.max-attempts 必须在 3~10");
        require(grantTtlSeconds >= 300 && grantTtlSeconds <= 86400,
                "mail.verification.grant-ttl-seconds 必须在 300~86400");
        require(dailyPerEmail >= 1 && dailyPerEmail <= 100, "mail.verification.daily-per-email 必须在 1~100");
        require(maxGrantsPerEmail >= 1 && maxGrantsPerEmail <= 10, "mail.verification.max-grants-per-email 必须在 1~10");
        require(maxGrants >= 100 && maxGrants <= 200000, "mail.verification.max-grants 必须在 100~200000");
        require(maxOrdersPerLookup >= 1 && maxOrdersPerLookup <= 500,
                "mail.verification.max-orders-per-lookup 必须在 1~500");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
