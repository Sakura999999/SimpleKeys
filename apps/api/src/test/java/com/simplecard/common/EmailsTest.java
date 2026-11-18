package com.simplecard.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮箱归一化测试。
 *
 * <p>重点是"同一个人"的判定：大小写、首尾空白、重复项都不能被当成不同用户。
 * 这里定的是规则，仓库查询与访问控制都依赖它。
 */
class EmailsTest {

    @Test
    void lowercasesAndTrims() {
        assertEquals("user@qq.com", Emails.normalize("  User@QQ.COM  "));
    }

    @Test
    void nullAndBlankBecomeNull() {
        assertNull(Emails.normalize(null));
        assertNull(Emails.normalize(""));
        assertNull(Emails.normalize("   "));
    }

    @Test
    void normalizeAllDropsBlanksAndDuplicates() {
        List<String> result = Emails.normalizeAll(Arrays.asList(
                "A@qq.com", "a@QQ.com", " ", null, "b@163.com", "b@163.com"));
        assertEquals(List.of("a@qq.com", "b@163.com"), result);
    }

    @Test
    void normalizeAllHandlesEmptyInput() {
        assertTrue(Emails.normalizeAll(null).isEmpty());
        assertTrue(Emails.normalizeAll(List.of()).isEmpty());
    }

    @Test
    void sameAddressIgnoresCaseAndSpace() {
        assertTrue(Emails.sameAddress(" Foo@QQ.com ", "foo@qq.com"));
        assertFalse(Emails.sameAddress("foo@qq.com", "bar@qq.com"));
        assertFalse(Emails.sameAddress(null, "foo@qq.com"));
        assertFalse(Emails.sameAddress("foo@qq.com", null));
    }

    /** 子域不同就是不同地址，不能被"看起来像"糊弄过去 */
    @Test
    void doesNotTreatSimilarDomainsAsSame() {
        assertFalse(Emails.sameAddress("a@mail.qq.com", "a@qq.com"));
    }
}
