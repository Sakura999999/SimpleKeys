package com.simplecard.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 签名比较工具 —— 恒定时间，避免按字节短路带来的计时侧信道。
 *
 * <p><b>为什么不能直接用 {@code equals} / {@code equalsIgnoreCase}：</b>
 * {@code String.equals} 逐字符比较并在第一个不同处立即返回。攻击者能反复提交
 * 不同签名、统计响应耗时，逐位逼近正确值。支付回调签名是「伪造入账」的唯一门槛，
 * 这个门槛不应该有可比对的时间差。
 *
 * <p><b>为什么保留大小写不敏感：</b>易支付与 BEpusdt 的 MD5 摘要大小写不统一
 * （有的网关回传大写 hex），改判定方式时不能顺手把合法回调拒掉。
 *
 * <p><b>长度不同仍会提前返回：</b>{@link MessageDigest#isEqual} 在长度不等时直接 false。
 * 这是可接受的：MD5 hex 长度固定 32，长度本身不是秘密，泄露不了任何摘要内容。
 */
public final class Signatures {

    private Signatures() {
    }

    /**
     * 恒定时间的大小写不敏感比较。
     *
     * @param expected 本地算出的签名（null 视为不通过）
     * @param actual   对方提交的签名（null 或空视为不通过）
     * @return 两者在忽略 ASCII 大小写后是否完全相同
     */
    public static boolean equalsIgnoreCaseConstantTime(String expected, String actual) {
        if (expected == null || actual == null || actual.isEmpty()) {
            return false;
        }
        byte[] a = expected.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
