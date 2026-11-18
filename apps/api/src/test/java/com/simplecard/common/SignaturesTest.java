package com.simplecard.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名比较的语义测试。
 *
 * <p>这里断言的不是"实现用了恒定时间"（时序本身测不了），而是<b>换成恒定时间后行为不变</b>：
 * 合法签名照样通过、大小写照样兼容、空值照样拒绝。改这类底层比较最怕的是
 * "安全了但把正常回调拒了"，那会直接导致用户付了钱不到账。
 */
class SignaturesTest {

    private static final String EXPECTED = "d41d8cd98f00b204e9800998ecf8427e";

    @Test
    void acceptsExactMatch() {
        assertTrue(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, EXPECTED));
    }

    @Test
    void acceptsUppercaseBecauseGatewaysDiffer() {
        assertTrue(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, EXPECTED.toUpperCase()));
        assertTrue(Signatures.equalsIgnoreCaseConstantTime(EXPECTED.toUpperCase(), EXPECTED));
    }

    @Test
    void rejectsWrongSignature() {
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, "0".repeat(32)));
    }

    /** 只差最后一位也要拒——这是"逐位逼近"最容易漏掉的场景 */
    @Test
    void rejectsAlmostCorrectSignature() {
        String almost = EXPECTED.substring(0, 31) + (EXPECTED.endsWith("e") ? "f" : "e");
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, almost));
    }

    @Test
    void rejectsNullAndEmpty() {
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, null));
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, ""));
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(null, EXPECTED));
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(null, null));
    }

    /** 长度不同直接不通过，不能因为抛出数组越界变成 500 */
    @Test
    void rejectsDifferentLength() {
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, EXPECTED.substring(0, 16)));
        assertFalse(Signatures.equalsIgnoreCaseConstantTime(EXPECTED, EXPECTED + "00"));
    }
}
