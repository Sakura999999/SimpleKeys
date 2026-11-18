package com.simplecard.controller;

import com.simplecard.config.MailChannelProperties;
import com.simplecard.config.MailExecutorConfig;
import com.simplecard.service.mail.MailDeliveryStats;
import com.simplecard.service.mail.MailQueueGuard;
import com.simplecard.service.mail.MailFailureType;
import com.simplecard.service.mail.MailDeliveryResult;
import com.simplecard.service.mail.MailPurpose;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 发信运维接口的输出契约测试。
 *
 * <p>用 standalone MockMvc：这里要验的是「字段真的对得上、空配置不炸」，不需要起 Spring 上下文，
 * 也不需要 ADMIN 令牌。鉴权由 {@code /admin/**} 的全局规则保证，另测。
 */
class AdminMailControllerTest {

    private final MailChannelProperties properties = new MailChannelProperties();
    private final MailQueueGuard guard = new MailQueueGuard();
    private final MailDeliveryStats stats = new MailDeliveryStats(new SimpleMeterRegistry());

    private ThreadPoolTaskExecutor verification;
    private ThreadPoolTaskExecutor notification;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MailExecutorConfig config = new MailExecutorConfig();
        verification = config.mailVerificationExecutor(guard);
        notification = config.mailNotificationExecutor(guard);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminMailController(stats, guard, properties, verification, notification))
                .build();
    }

    @AfterEach
    void tearDown() {
        verification.shutdown();
        notification.shutdown();
    }

    @Test
    void reportsZeroesWithNoTraffic() throws Exception {
        mockMvc.perform(get("/admin/mail/delivery-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.delivery.sent").value(0))
                .andExpect(jsonPath("$.data.delivery.failed").value(0))
                .andExpect(jsonPath("$.data.queue.total_rejected").value(0))
                .andExpect(jsonPath("$.data.pools.verification.queue_capacity").value(500))
                .andExpect(jsonPath("$.data.pools.notification.queue_capacity").value(2000));
    }

    @Test
    void reflectsFailuresAndQueueDrops() throws Exception {
        stats.record("a@example.com", MailPurpose.NOTIFICATION,
                MailDeliveryResult.fail("domestic", MailFailureType.AUTH_FAILED, 1, "535 auth"));
        guard.recordRejection(MailPurpose.VERIFICATION, "pool full");

        mockMvc.perform(get("/admin/mail/delivery-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delivery.failed").value(1))
                .andExpect(jsonPath("$.data.delivery.alert_required").value(1))
                .andExpect(jsonPath("$.data.delivery.failure_breakdown.AUTH_FAILED").value(1))
                .andExpect(jsonPath("$.data.queue.verification_rejected").value(1));
    }

    /** 没有配通道时，channels 是空对象而不是 null —— 前端/脚本不必判空 */
    @Test
    void emptyChannelConfigIsEmptyObject() throws Exception {
        mockMvc.perform(get("/admin/mail/delivery-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channels").isMap())
                .andExpect(jsonPath("$.data.from.verification").value(""));
    }
}
