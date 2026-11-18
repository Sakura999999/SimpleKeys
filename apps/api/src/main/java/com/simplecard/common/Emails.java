package com.simplecard.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 邮箱归一化 —— 全站唯一的"什么算同一个邮箱"的定义。
 *
 * <p><b>为什么必须统一：</b>同一个邮箱在系统里有两条路径会碰到它——下单时写入，
 * 查单时匹配。此前写库存原样、查询有的用 {@code lower()}、有的用大小写敏感的
 * {@code in}，结果是 {@code Foo@QQ.com} 下的单，用 {@code foo@qq.com} 查不到，
 * 而用大写查又能绕过「同邮箱待支付订单上限」。同一件事两套规矩，迟早对不上账。
 *
 * <p><b>为什么只做 trim + 小写：</b>按 RFC 5321，local part 理论上区分大小写，
 * 但现实中所有主流邮箱（QQ、163、Gmail、Outlook）都按不区分处理。发卡站面向普通用户，
 * 按现实语义处理才不会出现"我明明填对了却查不到订单"。
 *
 * <p><b>为什么不在这里校验格式：</b>本类只负责归一化。格式校验属于入参校验，
 * 混在一起会让调用方误以为"归一化成功 = 邮箱合法"。
 */
public final class Emails {

    private Emails() {
    }

    /** 归一化单个邮箱；null/空白返回 null */
    public static String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** 批量归一化，自动丢弃 null/空白项并去重（保持原顺序） */
    public static List<String> normalizeAll(Collection<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(emails.size());
        for (String email : emails) {
            String normalized = normalize(email);
            if (normalized != null && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    /** 两个邮箱是否指向同一个地址（任一为 null 视为不等） */
    public static boolean sameAddress(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        return left != null && left.equals(right);
    }
}
