package com.simplecard.service.mail;

import com.simplecard.config.MailChannelProperties;
import com.simplecard.config.MailSenderRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.ConnectException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 发送编排的行为契约：重试次数、不重试的边界、跨通道降级、硬退抑制。
 *
 * <p>这些是「换 SMTP 服务商」之外真正决定可用性的部分：配置正确但网络抖一次就丢信，
 * 或者把永久错误当临时错误反复重试拖垮发件域信誉。
 */
class MailDispatcherTest {

    private MailChannelProperties props;
    private MailSenderRouter router;
    private MailDeliveryStats stats;
    private MailDispatcher dispatcher;
    private JavaMailSenderImpl sender;
    private MailSenderRouter.Route route;

    @BeforeEach
    void setUp() {
        props = new MailChannelProperties();
        props.setEnabled(true);
        props.getRetry().setMaxAttempts(3);
        props.getRetry().setBackoffMs(1);
        props.getRetry().setMaxBackoffMs(2);

        router = mock(MailSenderRouter.class);
        stats = new MailDeliveryStats(new SimpleMeterRegistry());
        dispatcher = new MailDispatcher(router, props, stats);

        sender = mock(JavaMailSenderImpl.class);
        // 每次都要新实例：复用同一个 MimeMessage 会让后一次覆盖前一次的 From，
        // 使「发件人分离」的断言永远通过
        when(sender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        route = new MailSenderRouter.Route("domestic", sender, List.of("qq.com"), false, "verify@test.local");

        when(router.hasUsableChannel()).thenReturn(true);
        when(router.routes()).thenReturn(List.of(route));
        when(router.route(any())).thenReturn(route);
    }

    private MailMessage message(String to) {
        return MailMessage.builder()
                .to(to)
                .subject("测试")
                .html("<p>hi</p>")
                .purpose(MailPurpose.VERIFICATION)
                .build();
    }

    @Test
    void successOnFirstAttempt() {
        doNothing().when(sender).send(any(MimeMessage.class));

        MailDeliveryResult result = dispatcher.send(message("buyer@qq.com"));

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.channel()).isEqualTo("domestic");
        verify(sender, times(1)).send(any(MimeMessage.class));
        assertThat(stats.snapshot()).containsEntry("sent", 1L);
    }

    @Test
    void permanentRecipientErrorIsNotRetried() {
        doThrow(new MailSendException("550 5.1.1 <ghost@qq.com>: Recipient address rejected"))
                .when(sender).send(any(MimeMessage.class));

        MailDeliveryResult result = dispatcher.send(message("ghost@qq.com"));

        assertThat(result.success()).isFalse();
        assertThat(result.failure()).isEqualTo(MailFailureType.RECIPIENT_REJECTED);
        // 关键：只尝试一次。重试会把硬退率翻三倍
        assertThat(result.attempts()).isEqualTo(1);
        verify(sender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void temporaryErrorIsRetriedUntilSuccess() {
        doThrow(new MailSendException("421 4.7.0 Too many connections"))
                .doThrow(new MailSendException("421 4.7.0 Too many connections"))
                .doNothing()
                .when(sender).send(any(MimeMessage.class));

        MailDeliveryResult result = dispatcher.send(message("buyer@qq.com"));

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        verify(sender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    void givesUpAfterMaxAttempts() {
        doThrow(new MailSendException("421 4.7.0 Too many connections"))
                .when(sender).send(any(MimeMessage.class));

        MailDeliveryResult result = dispatcher.send(message("buyer@qq.com"));

        assertThat(result.success()).isFalse();
        assertThat(result.failure()).isEqualTo(MailFailureType.RATE_LIMITED);
        assertThat(result.attempts()).isEqualTo(3);
    }

    @Test
    void connectFailureFailsOverToAnotherChannel() {
        JavaMailSenderImpl backupSender = mock(JavaMailSenderImpl.class);
        when(backupSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doNothing().when(backupSender).send(any(MimeMessage.class));
        MailSenderRouter.Route backup =
                new MailSenderRouter.Route("overseas", backupSender, List.of(), true, "verify@test.local");

        doThrow(new MailSendException("connect failed", new ConnectException("Connection refused")))
                .when(sender).send(any(MimeMessage.class));
        when(router.routes()).thenReturn(List.of(route, backup));

        MailDeliveryResult result = dispatcher.send(message("buyer@qq.com"));

        assertThat(result.success()).isTrue();
        assertThat(result.channel()).isEqualTo("overseas");
        verify(backupSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void hardBounceSuppressesLaterSends() {
        doThrow(new MailSendException("550 5.1.1 user unknown"))
                .when(sender).send(any(MimeMessage.class));

        MailDeliveryResult first = dispatcher.send(message("ghost@qq.com"));
        assertThat(first.success()).isFalse();

        // 第二次应被拦在发送之前，不再产生一次硬退
        MailDeliveryResult second = dispatcher.send(message("ghost@qq.com"));
        assertThat(second.isSkipped()).isTrue();
        verify(sender, times(1)).send(any(MimeMessage.class));
        assertThat(stats.isSuppressed("ghost@qq.com")).isTrue();
    }

    @Test
    void suppressedCheckIsCaseInsensitive() {
        stats.suppress("Ghost@QQ.com", "test");
        assertThat(stats.isSuppressed("ghost@qq.com")).isTrue();
    }

    @Test
    void disabledMailSkipsWithoutTouchingSender() {
        props.setEnabled(false);

        MailDeliveryResult result = dispatcher.send(message("buyer@qq.com"));

        assertThat(result.isSkipped()).isTrue();
        verifyNoInteractions(sender);
    }

    @Test
    void noUsableChannelSkipsGracefully() {
        when(router.hasUsableChannel()).thenReturn(false);

        MailDeliveryResult result = dispatcher.send(message("buyer@qq.com"));

        assertThat(result.isSkipped()).isTrue();
        verifyNoInteractions(sender);
    }

    @Test
    void verificationUsesDedicatedFromAddressAndNotificationUsesItsOwn() throws Exception {
        props.getFrom().setVerification("verify@brand.test");
        props.getFrom().setNotification("noreply@brand.test");
        doNothing().when(sender).send(any(MimeMessage.class));

        dispatcher.send(message("buyer@qq.com"));
        dispatcher.send(MailMessage.builder()
                .to("buyer@qq.com").subject("订单").html("<p>x</p>")
                .purpose(MailPurpose.NOTIFICATION).build());

        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(captor.capture());

        // From 决定信誉归属，两类邮件串用发件人会让一类被投诉时拖累另一类
        assertThat(captor.getAllValues().get(0).getFrom()[0].toString()).contains("verify@brand.test");
        assertThat(captor.getAllValues().get(1).getFrom()[0].toString()).contains("noreply@brand.test");
    }

    @Test
    void fallsBackToChannelFromWhenDedicatedAddressMissing() throws Exception {
        doNothing().when(sender).send(any(MimeMessage.class));

        dispatcher.send(message("buyer@qq.com"));

        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        // 旧部署只有一个 MAIL_USERNAME，升级后行为不能变
        assertThat(captor.getValue().getFrom()[0].toString()).contains("verify@test.local");
    }
}
