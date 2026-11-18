package com.simplecard.service.mail;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把任意 SMTP 异常翻译成 {@link MailFailureType}。
 *
 * <p><b>为什么不直接用异常类型：</b>Spring 会把各层异常包成 {@code MailSendException}，
 * 真正的结论藏在 cause 链和 SMTP 返回码里。同一个 {@code MailSendException} 既可能是
 * 「连不上」（该重试、该换通道），也可能是「收件人不存在」（绝不能重试）。
 * 分不清这两者，就是拿发件域的信誉在赌。
 *
 * <p>纯静态、无状态，便于单独测试。
 */
@Slf4j
public final class MailFailureClassifier {

    private MailFailureClassifier() {
    }

    /** 从消息文本里提取 SMTP 三位返回码（2xx/4xx/5xx），避免误匹配 IP、端口、时间 */
    private static final Pattern SMTP_CODE =
            Pattern.compile("(?:^|[\\s(<:])([245]\\d{2})(?:[\\s>)\\-.:]|$)");

    /**
     * 聚合多个结论时的优先级：越靠前越"具体"。
     *
     * <p>收件人拒绝最具体 —— 说明对话已经走到 RCPT TO，前面的连接与认证必然成功；
     * 连接失败最笼统，容易被内层包装误判，放最后。
     */
    private static final MailFailureType[] PRIORITY = {
            MailFailureType.RECIPIENT_REJECTED,
            MailFailureType.CONTENT_REJECTED,
            MailFailureType.AUTH_FAILED,
            MailFailureType.RATE_LIMITED,
            MailFailureType.CONNECT_FAILED
    };

    public static MailFailureType classify(Throwable error) {
        if (error == null) {
            return MailFailureType.UNKNOWN;
        }

        Set<MailFailureType> found = Collections.newSetFromMap(new LinkedHashMap<>());
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> stack = new ArrayDeque<>();
        stack.push(error);

        while (!stack.isEmpty()) {
            Throwable t = stack.pop();
            if (t == null || !visited.add(t)) {
                continue;
            }

            MailFailureType direct = classifySingle(t);
            if (direct != null) {
                found.add(direct);
            }

            // Spring 把每个收件人的异常放在 messageExceptions 里，cause 链未必能走到
            if (t instanceof MailSendException mse) {
                Exception[] nested = mse.getMessageExceptions();
                if (nested != null) {
                    for (Exception e : nested) {
                        stack.push(e);
                    }
                }
            }
            if (t.getCause() != null) {
                stack.push(t.getCause());
            }
            for (Throwable s : t.getSuppressed()) {
                stack.push(s);
            }
        }

        for (MailFailureType candidate : PRIORITY) {
            if (found.contains(candidate)) {
                return candidate;
            }
        }
        return MailFailureType.UNKNOWN;
    }

    /** 单个异常节点的判定，返回 null 表示"这一层看不出结论" */
    private static MailFailureType classifySingle(Throwable t) {
        if (t instanceof AuthenticationFailedException || t instanceof MailAuthenticationException) {
            return MailFailureType.AUTH_FAILED;
        }

        if (t instanceof SendFailedException sfe && hasInvalidRecipients(sfe)) {
            return MailFailureType.RECIPIENT_REJECTED;
        }

        // 网络层：没连上，换通道可能就好了
        if (t instanceof SocketTimeoutException
                || t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof NoRouteToHostException
                || (t instanceof InterruptedIOException && !(t instanceof SocketTimeoutException))) {
            return MailFailureType.CONNECT_FAILED;
        }

        // 文本返回码：Spring 的 MailSendException 不是 MessagingException，
        // 若只看类型，「550 收件人不存在」会掉到 UNKNOWN，从而被当成可重试错误
        int code = extractReturnCode(t);
        if (code > 0) {
            MailFailureType byCode = bySmtpCode(code);
            if (byCode != null) {
                return byCode;
            }
        }

        // 兜底：类名带 Connect/Timeout 的（不同 JavaMail 实现的类名不完全统一）
        String simple = t.getClass().getSimpleName();
        if (simple.contains("Connect") || simple.contains("Timeout")) {
            return MailFailureType.CONNECT_FAILED;
        }

        // 最后兜底：JavaMail 在拿不到返回码时，把原因写在消息文本里
        String msg = t.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase(Locale.ROOT);
            if (lower.contains("could not connect")
                    || lower.contains("connection refused")
                    || lower.contains("connect timed out")
                    || lower.contains("connection timed out")
                    || lower.contains("unknown host")
                    || lower.contains("network is unreachable")) {
                return MailFailureType.CONNECT_FAILED;
            }
        }

        return null;
    }

    private static boolean hasInvalidRecipients(SendFailedException sfe) {
        var invalid = sfe.getInvalidAddresses();
        if (invalid != null && invalid.length > 0) {
            return true;
        }
        var validUnsent = sfe.getValidUnsentAddresses();
        // 有"有效但未发出"的地址时还要看返回码：可能是 4xx 临时故障
        return validUnsent == null || validUnsent.length == 0;
    }

    static int extractReturnCode(Throwable t) {
        // com.sun.mail.smtp.SMTPSendFailedException 有结构化返回码；用反射读，避免绑定具体实现
        try {
            var m = t.getClass().getMethod("getReturnCode");
            Object v = m.invoke(t);
            if (v instanceof Integer i && i > 0) {
                return i;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 非该类型，继续解析文本
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return -1;
        }
        Matcher mt = SMTP_CODE.matcher(msg);
        return mt.find() ? Integer.parseInt(mt.group(1)) : -1;
    }

    static MailFailureType bySmtpCode(int code) {
        return switch (code) {
            // 4xx 临时性：稍后重试可能成功
            case 421, 450, 451, 452 -> MailFailureType.RATE_LIMITED;
            // 认证/授权
            case 530, 534, 535 -> MailFailureType.AUTH_FAILED;
            // 收件人层面永久拒绝
            case 550, 551, 552, 553 -> MailFailureType.RECIPIENT_REJECTED;
            // 内容被判垃圾/违规
            case 554 -> MailFailureType.CONTENT_REJECTED;
            default -> {
                if (code >= 500 && code < 600) {
                    yield MailFailureType.RECIPIENT_REJECTED;
                }
                if (code >= 400 && code < 500) {
                    yield MailFailureType.RATE_LIMITED;
                }
                yield null;
            }
        };
    }
}
