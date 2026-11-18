package com.simplecard.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 邮件可用性判定 — 全站唯一实现。
 *
 * <p><b>为什么不能只看 {@code mail.enabled}：</b>打开开关但没填账号时，邮箱验证码入口
 * 会对用户开放却永远收不到信，比直接关闭更糟。因此把「开关打开」与「参数真的填了」
 * 分成两层，只有两层都成立才发信、才开放入口。
 *
 * <p><b>多通道下的判定：</b>可用性收敛到「至少有一个通道可用」，
 * 具体判定委托给 {@link MailSenderRouter}，避免两处逻辑不一致。
 * 占位符集合在此处集中定义，供 {@link MailChannelProperties.Channel} 共用。
 */
@Slf4j
@Component
public class MailAvailability {

    /** 文档与示例中出现过的占位符主机 */
    public static final Set<String> PLACEHOLDER_HOSTS = Set.of(
            "", "mail.example.com", "smtp.example.com", "example.com");

    /** 占位符口令，与 application.yml 默认值保持一致 */
    public static final Set<String> PLACEHOLDER_PASSWORDS = Set.of("", "your_password", "your-password");

    private final MailChannelProperties properties;
    private final MailSenderRouter router;

    public MailAvailability(MailChannelProperties properties, MailSenderRouter router) {
        this.properties = properties;
        this.router = router;
    }

    /** 至少有一个通道填全了参数 */
    public boolean isConfigured() {
        return router.hasUsableChannel();
    }

    /** 开关打开且至少有一个通道可用，才允许发信与开放入口 */
    public boolean isAvailable() {
        return properties.isEnabled() && router.hasUsableChannel();
    }

    /** 供日志使用，不打印口令 */
    public String describe() {
        return "enabled=" + properties.isEnabled()
                + ", channels=" + router.describe();
    }
}
