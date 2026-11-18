package com.simplecard.service.mail;

import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败分类的判定契约。
 *
 * <p>这里每一条断言都直接对应一个业务后果：分错了就会「向不存在的地址反复重试」，
 * 或「把配置错误当成网络抖动」，两者都会累积硬退或掩盖故障。
 */
class MailFailureClassifierTest {

    @Test
    void recipientRejectedIsNeverRetried() {
        // 550 是最典型的永久错误：地址不存在。重试只会累积硬退
        assertThat(MailFailureClassifier.classify(
                new MailSendException("550 5.1.1 <ghost@example.com>: Recipient address rejected")))
                .isEqualTo(MailFailureType.RECIPIENT_REJECTED);
        assertThat(MailFailureType.RECIPIENT_REJECTED.retryable()).isFalse();
        assertThat(MailFailureType.RECIPIENT_REJECTED.channelSwappable()).isFalse();
    }

    @Test
    void sendFailedExceptionWithInvalidAddressesIsRecipientRejected() throws Exception {
        SendFailedException e = new SendFailedException("rejected", null, null, null,
                new InternetAddress[]{new InternetAddress("bad@example.com")});
        assertThat(MailFailureClassifier.classify(new MailSendException("send failed", e)))
                .isEqualTo(MailFailureType.RECIPIENT_REJECTED);
    }

    @Test
    void temporaryCodesAreRateLimitedAndRetryable() {
        assertThat(MailFailureClassifier.classify(
                new MailSendException("421 4.7.0 Too many connections")))
                .isEqualTo(MailFailureType.RATE_LIMITED);
        assertThat(MailFailureClassifier.classify(
                new MailSendException("452 4.2.2 Mailbox full")))
                .isEqualTo(MailFailureType.RATE_LIMITED);
        assertThat(MailFailureType.RATE_LIMITED.retryable()).isTrue();
        assertThat(MailFailureType.RATE_LIMITED.channelSwappable()).isTrue();
    }

    @Test
    void authFailureIsFlaggedForAlert() {
        assertThat(MailFailureClassifier.classify(
                new MailSendException("535 5.7.8 Authentication credentials invalid")))
                .isEqualTo(MailFailureType.AUTH_FAILED);
        assertThat(MailFailureClassifier.classify(new MailAuthenticationException("bad credentials")))
                .isEqualTo(MailFailureType.AUTH_FAILED);
        assertThat(MailFailureType.AUTH_FAILED.needsAlert()).isTrue();
    }

    @Test
    void spamRejectionIsContentNotRecipient() {
        // 554 说明地址是好的，是内容被拒。归成收件人拒绝会误封正常用户
        assertThat(MailFailureClassifier.classify(
                new MailSendException("554 5.7.1 Message rejected as spam")))
                .isEqualTo(MailFailureType.CONTENT_REJECTED);
    }

    @Test
    void networkErrorsAreConnectFailures() {
        assertThat(MailFailureClassifier.classify(
                new MailSendException("connect failed", new ConnectException("Connection refused"))))
                .isEqualTo(MailFailureType.CONNECT_FAILED);
        assertThat(MailFailureClassifier.classify(
                new MailSendException("read timed out", new SocketTimeoutException("Read timed out"))))
                .isEqualTo(MailFailureType.CONNECT_FAILED);
        assertThat(MailFailureClassifier.classify(
                new MailSendException("dns", new UnknownHostException("smtp.invalid"))))
                .isEqualTo(MailFailureType.CONNECT_FAILED);
    }

    @Test
    void ipAndPortInMessageDoNotBecomeSmtpCode() {
        // 消息里常带 IP 与端口。若把它们误读成返回码，分类就完全错了
        MailFailureType t = MailFailureClassifier.classify(
                new MailSendException("Could not connect to SMTP host: 192.168.1.10, port: 465, response: -1"));
        assertThat(t).isEqualTo(MailFailureType.CONNECT_FAILED);
    }

    @Test
    void unknownErrorIsConservative() {
        assertThat(MailFailureClassifier.classify(new IllegalStateException("boom")))
                .isEqualTo(MailFailureType.UNKNOWN);
        assertThat(MailFailureType.UNKNOWN.retryable()).isFalse();
        assertThat(MailFailureClassifier.classify(null)).isEqualTo(MailFailureType.UNKNOWN);
    }

    @Test
    void returnCodeMappingIsExplicit() {
        assertThat(MailFailureClassifier.bySmtpCode(550)).isEqualTo(MailFailureType.RECIPIENT_REJECTED);
        assertThat(MailFailureClassifier.bySmtpCode(535)).isEqualTo(MailFailureType.AUTH_FAILED);
        assertThat(MailFailureClassifier.bySmtpCode(421)).isEqualTo(MailFailureType.RATE_LIMITED);
        assertThat(MailFailureClassifier.bySmtpCode(554)).isEqualTo(MailFailureType.CONTENT_REJECTED);
        assertThat(MailFailureClassifier.bySmtpCode(250)).isNull();
    }
}
