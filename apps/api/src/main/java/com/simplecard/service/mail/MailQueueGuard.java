package com.simplecard.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 发信任务排队守卫 — 记录「因为线程池满而被丢弃」的信。
 *
 * <p><b>为什么要有这个东西：</b>发信线程池必须有界（否则队列无限增长，最终 OOM），
 * 而有界池在洪峰时必然会拒绝任务。拒绝如果抛回调用线程，就会把
 * 「支付回调 / 查单接口」一起拖慢甚至报错；如果静默丢弃，又没人知道信丢了。
 * 所以这里的策略是：<b>丢弃 + 计数 + 分级日志</b>，让调用线程完全不受影响，
 * 同时把丢失量暴露到运维接口上。
 *
 * <p><b>两类信丢失的后果不同：</b>
 * <ul>
 *   <li>验证码：用户重发一次即可，属可自愈，记 WARN</li>
 *   <li>发货通知：用户拿不到卡密，属业务损失，记 ERROR 并带订单线索，需人工补发</li>
 * </ul>
 * 因此两类分开计数，方便判断该扩容还是该限流。
 */
@Slf4j
@Component
public class MailQueueGuard {

    private final LongAdder verificationRejected = new LongAdder();
    private final LongAdder notificationRejected = new LongAdder();
    private final LongAdder suppressedRejected = new LongAdder();

    /**
     * 记录一次任务被丢弃。
     *
     * <p>调用点在 {@code RejectedExecutionHandler} 里，运行在提交任务的线程上，
     * 所以这里绝对不能抛异常——抛了就等于让发信把业务请求搞挂。
     */
    public void recordRejection(MailPurpose purpose, String detail) {
        if (purpose == MailPurpose.NOTIFICATION) {
            notificationRejected.increment();
            log.error("Mail queue full, delivery notification dropped ({}). Needs manual resend", detail);
        } else if (purpose == MailPurpose.VERIFICATION) {
            verificationRejected.increment();
            log.warn("Mail queue full, verification code dropped ({}). User can request again", detail);
        } else {
            suppressedRejected.increment();
            log.warn("Mail queue full, {} mail dropped ({})", purpose, detail);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("verification_rejected", verificationRejected.sum());
        snap.put("notification_rejected", notificationRejected.sum());
        snap.put("other_rejected", suppressedRejected.sum());
        snap.put("total_rejected", verificationRejected.sum() + notificationRejected.sum() + suppressedRejected.sum());
        return snap;
    }
}
