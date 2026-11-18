package com.simplecard.service.impl;

import com.simplecard.entity.CardKey;
import com.simplecard.entity.Order;
import com.simplecard.entity.OrderItem;
import com.simplecard.repository.CardKeyRepository;
import com.simplecard.repository.OrderItemRepository;
import com.simplecard.repository.OrderRepository;
import com.simplecard.repository.SiteConfigRepository;
import com.simplecard.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final com.simplecard.service.mail.MailDispatcher mailDispatcher;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CardKeyRepository cardKeyRepository;
    private final SiteConfigRepository siteConfigRepository;
    private final com.simplecard.config.MailAvailability mailAvailability;

    @Value("${mail.site-url:https://example.com}")
    private String siteUrl;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 发货通知走独立线程池。
     *
     * <p>不共用默认池是有意的：验证码会被刷、会排队，而发货通知是「用户已经付过钱」的信。
     * 两者共用一个池时，验证码洪峰会把它堵在后面（见 {@link com.simplecard.config.MailExecutorConfig}）。
     */
    @Async(com.simplecard.config.MailExecutorConfig.NOTIFICATION_EXECUTOR)
    @Override
    public void sendDeliveryEmail(UUID orderId) {
        if (!mailAvailability.isAvailable()) {
            return;
        }

        try {
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null || order.getStatus() != com.simplecard.constant.OrderStatus.DELIVERED || order.getEmail() == null || order.getEmail().isBlank()) {
                log.warn("Cannot send delivery email: order {} not found or no email", orderId);
                return;
            }

            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
            List<CardKey> keys = cardKeyRepository.findByOrderId(orderId).stream().filter(k -> k.getStatus() == com.simplecard.constant.CardKeyStatus.SOLD).toList();
            Map<UUID, OrderItem> itemMap = items.stream()
                    .collect(Collectors.toMap(OrderItem::getId, i -> i));
            Map<UUID, List<CardKey>> grouped = keys.stream()
                    .filter(k -> k.getOrderItemId() != null)
                    .collect(Collectors.groupingBy(CardKey::getOrderItemId));

            String siteName = siteConfigRepository.findByConfigKey("site_name")
                    .map(c -> c.getConfigValue())
                    .orElse("简易发卡");

            String html = buildHtml(order, itemMap, grouped, siteName);

            // 交给统一发送器：按收件域选通道、失败分类、退避重试、硬退抑制都在那里完成
            mailDispatcher.send(com.simplecard.service.mail.MailMessage.builder()
                    .to(order.getEmail())
                    .subject("【" + siteName + "】订单发货通知 - " + orderId.toString().substring(0, 8))
                    .html(html)
                    .purpose(com.simplecard.service.mail.MailPurpose.NOTIFICATION)
                    .build());
        } catch (Exception e) {
            log.error("Unexpected error preparing delivery email for order {}: {}", orderId, e.getMessage(), e);
        }
    }

    /**
     * 发送查单验证码。
     *
     * <p><b>异步执行有两个目的：</b>第一，SMTP 握手可能要几秒，同步会让接口在
     * 「该邮箱确实有订单」时才变慢，形成可观测的时序侧信道；第二，发信失败不能让
     * 接口报错——否则调用方从「发送成功/失败」就能推断订单是否存在。
     *
     * <p>因此本方法不返回是否成功，失败只记日志与计数。
     *
     * <p>走独立的验证码线程池：这个池被洪峰打满时只丢验证码（用户可重发），
     * 不会挤占发货通知。
     */
    @Async(com.simplecard.config.MailExecutorConfig.VERIFICATION_EXECUTOR)
    @Override
    public void sendVerificationCode(String toEmail, String code, int ttlMinutes) {
        if (!mailAvailability.isAvailable()) {
            log.debug("Skip verification code mail: SMTP unavailable ({})", mailAvailability.describe());
            return;
        }
        try {
            String siteName = siteConfigRepository.findByConfigKey("site_name")
                    .map(c -> c.getConfigValue())
                    .orElse("简易发卡");

            // 实际发送与失败处理都在 MailDispatcher：这里只负责内容
            mailDispatcher.send(com.simplecard.service.mail.MailMessage.builder()
                    .to(toEmail)
                    .subject("【" + siteName + "】订单查询验证码")
                    .html(buildVerificationHtml(code, ttlMinutes, siteName))
                    .purpose(com.simplecard.service.mail.MailPurpose.VERIFICATION)
                    .build());
        } catch (Exception e) {
            // 不打印验证码本身，避免日志成为获取凭证的旁路
            log.error("Failed to prepare verification code mail to {}: {}", maskEmail(toEmail), e.getMessage());
        }
    }

    private String buildVerificationHtml(String code, int ttlMinutes, String siteName) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"></head>")
          .append("<body style=\"margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;\">")
          .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f4f7;padding:24px 0;\">")
          .append("<tr><td align=\"center\">")
          .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">")
          .append("<tr><td style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:28px 40px;text-align:center;\">")
          .append("<h1 style=\"margin:0;color:#ffffff;font-size:22px;font-weight:600;letter-spacing:1px;\">")
          .append(escapeHtml(siteName)).append("</h1></td></tr>")
          .append("<tr><td style=\"padding:32px 40px 8px;\">")
          .append("<h2 style=\"margin:0 0 8px;color:#333333;font-size:18px;font-weight:600;\">订单查询验证码</h2>")
          .append("<p style=\"margin:0;color:#666666;font-size:14px;line-height:1.6;\">您正在其他设备上查询订单，请在页面中输入以下验证码：</p>")
          .append("</td></tr>")
          .append("<tr><td style=\"padding:20px 40px;\">")
          .append("<div style=\"background-color:#f8f9fa;border:1px solid #e8e8e8;border-radius:6px;padding:20px;text-align:center;\">")
          .append("<span style=\"font-family:'Courier New',Courier,monospace;font-size:32px;font-weight:700;letter-spacing:8px;color:#333333;\">")
          .append(escapeHtml(code)).append("</span></div></td></tr>")
          .append("<tr><td style=\"padding:0 40px 24px;\">")
          .append("<div style=\"background-color:#fff8e1;border:1px solid #ffe082;border-radius:6px;padding:12px 16px;font-size:13px;color:#f57f17;\">")
          .append("验证码 ").append(ttlMinutes).append(" 分钟内有效，仅可使用一次。")
          .append("<br>如非本人操作，请忽略本邮件——您的订单不会因此泄露；建议同时修改邮箱密码。</div></td></tr>")
          .append("<tr><td style=\"background-color:#f8f9fa;padding:20px 40px;text-align:center;border-top:1px solid #eee;\">")
          .append("<p style=\"margin:0 0 4px;color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复</p>")
          .append("<p style=\"margin:0;color:#bbb;font-size:11px;\">&copy; ")
          .append(java.time.Year.now().getValue()).append(" ").append(escapeHtml(siteName)).append("</p>")
          .append("</td></tr></table></td></tr></table></body></html>");
        return sb.toString();
    }

    /** 日志里只保留域名与首字符，避免邮箱地址在日志中成规模沉淀 */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "<empty>";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String name = email.substring(0, at);
        String domain = email.substring(at);
        return (name.length() <= 1 ? "*" : name.charAt(0) + "***") + domain;
    }

    private String buildHtml(Order order, Map<UUID, OrderItem> itemMap,
                             Map<UUID, List<CardKey>> grouped, String siteName) {
        UUID orderId = order.getId();
        BigDecimal amount = order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
        LocalDateTime createdAt = order.getCreatedAt();
        String orderUrl = siteUrl + "/order/query?orderId=" + orderId;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        sb.append("</head><body style=\"margin:0;padding:0;background-color:#f4f4f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;\">");

        // Container
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f4f7;padding:24px 0;\">");
        sb.append("<tr><td align=\"center\">");
        sb.append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.08);\">");

        // Header
        sb.append("<tr><td style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:32px 40px;text-align:center;\">");
        sb.append("<h1 style=\"margin:0;color:#ffffff;font-size:24px;font-weight:600;letter-spacing:1px;\">")
          .append(escapeHtml(siteName)).append("</h1>");
        sb.append("</td></tr>");

        // Title
        sb.append("<tr><td style=\"padding:32px 40px 0;\">");
        sb.append("<h2 style=\"margin:0 0 8px;color:#333333;font-size:20px;font-weight:600;\">订单发货通知</h2>");
        sb.append("<p style=\"margin:0;color:#666666;font-size:14px;line-height:1.6;\">您的订单已完成发货，以下是您购买的卡密信息：</p>");
        sb.append("</td></tr>");

        // Order info
        sb.append("<tr><td style=\"padding:24px 40px;\">");
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f8f9fa;border-radius:6px;padding:16px;\">");
        sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">订单编号</span></td>");
        sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;font-family:monospace;\">")
          .append(orderId).append("</span></td></tr>");
        sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">支付金额</span></td>");
        sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;font-weight:600;\">¥")
          .append(amount).append("</span></td></tr>");
        if (createdAt != null) {
            sb.append("<tr><td style=\"padding:4px 16px;\"><span style=\"color:#888;font-size:13px;\">下单时间</span></td>");
            sb.append("<td style=\"padding:4px 16px;text-align:right;\"><span style=\"color:#333;font-size:13px;\">")
              .append(createdAt.format(DATE_FMT)).append("</span></td></tr>");
        }
        sb.append("</table>");
        sb.append("</td></tr>");

        // Card keys grouped by product
        sb.append("<tr><td style=\"padding:0 40px 24px;\">");
        for (Map.Entry<UUID, List<CardKey>> entry : grouped.entrySet()) {
            OrderItem item = itemMap.get(entry.getKey());
            if (item == null) continue;

            String title = escapeHtml(item.getProductTitle());
            if (item.getSpecName() != null && !item.getSpecName().isBlank()) {
                title += " <span style=\"color:#888;font-size:12px;\">[" + escapeHtml(item.getSpecName()) + "]</span>";
            }

            sb.append("<div style=\"margin-bottom:16px;border:1px solid #e8e8e8;border-radius:6px;overflow:hidden;\">");
            sb.append("<div style=\"background-color:#f0f0f5;padding:10px 16px;font-size:14px;font-weight:600;color:#333;\">")
              .append(title).append("</div>");
            sb.append("<div style=\"padding:12px 16px;\">");
            for (CardKey key : entry.getValue()) {
                sb.append("<div style=\"background-color:#fafafa;border:1px solid #eee;border-radius:4px;padding:8px 12px;margin-bottom:6px;font-family:'Courier New',Courier,monospace;font-size:13px;color:#222;word-break:break-all;\">")
                  .append(escapeHtml(key.getContent())).append("</div>");
            }
            sb.append("</div></div>");
        }
        sb.append("</td></tr>");

        // Warning
        sb.append("<tr><td style=\"padding:0 40px 24px;\">");
        sb.append("<div style=\"background-color:#fff8e1;border:1px solid #ffe082;border-radius:6px;padding:12px 16px;font-size:13px;color:#f57f17;\">");
        sb.append("&#9888; 请妥善保管卡密信息，切勿泄露给他人。如有任何问题，请联系客服。");
        sb.append("</div></td></tr>");

        // CTA button
        sb.append("<tr><td style=\"padding:0 40px 32px;text-align:center;\">");
        sb.append("<a href=\"").append(escapeHtml(orderUrl)).append("\" style=\"display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#ffffff;text-decoration:none;padding:12px 32px;border-radius:6px;font-size:14px;font-weight:600;\">查看订单详情</a>");
        sb.append("</td></tr>");

        // Footer
        sb.append("<tr><td style=\"background-color:#f8f9fa;padding:20px 40px;text-align:center;border-top:1px solid #eee;\">");
        sb.append("<p style=\"margin:0 0 4px;color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复</p>");
        sb.append("<p style=\"margin:0;color:#bbb;font-size:11px;\">&copy; ").append(java.time.Year.now().getValue())
          .append(" ").append(escapeHtml(siteName)).append("</p>");
        sb.append("</td></tr>");

        sb.append("</table></td></tr></table>");
        sb.append("</body></html>");

        return sb.toString();
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
    }
}
