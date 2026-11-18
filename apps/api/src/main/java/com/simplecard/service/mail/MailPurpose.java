package com.simplecard.service.mail;

/**
 * 邮件用途 — 决定发件人身份，用于隔离不同类别邮件的发信信誉。
 *
 * <p>验证码与发货通知分开，是因为它们被用户处理的方式完全不同：验证码常被误标垃圾，
 * 发货通知不会。若共用一个发件人，验证码的投诉率会直接拖累发货通知的送达。
 */
public enum MailPurpose {

    /** 订单发货通知，含卡密 */
    NOTIFICATION,

    /** 身份验证类，含验证码 */
    VERIFICATION
}
