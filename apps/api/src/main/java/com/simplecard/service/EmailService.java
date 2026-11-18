package com.simplecard.service;

import java.util.UUID;

public interface EmailService {

    /** 发货通知（含卡密） */
    void sendDeliveryEmail(UUID orderId);

    /**
     * 访客订单找回的验证码。
     *
     * <p>实现必须是异步且吞掉异常：接口的可观测行为不能因「该邮箱是否有订单」而变化，
     * 否则等于提供订单存在性探测口。
     *
     * @param toEmail    收件邮箱
     * @param code       明文验证码，实现方不得写入日志
     * @param ttlMinutes 有效期（分钟），用于邮件正文提示
     */
    void sendVerificationCode(String toEmail, String code, int ttlMinutes);
}
