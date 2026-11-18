package com.simplecard.service.mail;

import lombok.Builder;

/**
 * 一封待发送的邮件。
 *
 * <p>不含发件人：发件人由 {@link MailDispatcher} 按 {@link #purpose()} 决定，
 * 避免调用方各自硬编码 `From` 导致信誉隔离失效。
 */
@Builder
public record MailMessage(
        String to,
        String subject,
        String html,
        MailPurpose purpose,
        /** 可选 Reply-To，留空则只设 From */
        String replyTo
) {
    public MailMessage {
        if (purpose == null) {
            purpose = MailPurpose.NOTIFICATION;
        }
    }
}
