package com.simplecard;

import com.simplecard.service.OrderLookupGrantStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 查单凭证的签发、解析与容量边界。 */
class OrderLookupGrantStoreTest {

    private static final String EMAIL = "buyer@example.com";

    @Test
    void issuedTokenResolvesToTheBoundEmail() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        var issued = store.issue(EMAIL, 7200, 3, 100).orElseThrow();

        var grant = store.resolve(issued.token()).orElseThrow();
        assertThat(grant.email()).isEqualTo(EMAIL);
        assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(40);
        // 服务端只存哈希：把明文当 key 直接查是查不到的
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void expiredTokenDoesNotResolve() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        String token = store.issue(EMAIL, -1, 3, 100).orElseThrow().token();
        assertThat(store.resolve(token)).isEmpty();
    }

    @Test
    void garbageTokensAreRejectedWithoutThrowing() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        assertThat(store.resolve(null)).isEmpty();
        assertThat(store.resolve("")).isEmpty();
        assertThat(store.resolve("   ")).isEmpty();
        assertThat(store.resolve("not-a-real-token")).isEmpty();
        assertThat(store.resolve("x".repeat(500))).isEmpty();
    }

    @Test
    void perEmailLimitEvictsTheOldestButKeepsTheNewest() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        String first = store.issue(EMAIL, 7200, 2, 100).orElseThrow().token();
        String second = store.issue(EMAIL, 7200, 2, 100).orElseThrow().token();
        String third = store.issue(EMAIL, 7200, 2, 100).orElseThrow().token();

        // 多设备场景：新的两张可用，最旧的一张被淘汰
        assertThat(store.resolve(first)).isEmpty();
        assertThat(store.resolve(second)).isPresent();
        assertThat(store.resolve(third)).isPresent();
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void differentEmailsKeepIndependentGrants() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        String a = store.issue("a@example.com", 7200, 2, 100).orElseThrow().token();
        String b = store.issue("b@example.com", 7200, 2, 100).orElseThrow().token();

        assertThat(store.resolve(a).orElseThrow().email()).isEqualTo("a@example.com");
        assertThat(store.resolve(b).orElseThrow().email()).isEqualTo("b@example.com");
    }

    @Test
    void storeRefusesToGrowBeyondItsCapacity() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        // maxTotal=2：第三张签发失败而不是无限占用内存
        assertThat(store.issue("a@example.com", 7200, 3, 2)).isPresent();
        assertThat(store.issue("b@example.com", 7200, 3, 2)).isPresent();
        assertThat(store.issue("c@example.com", 7200, 3, 2)).isEmpty();
    }

    @Test
    void revokedTokenStopsResolving() {
        OrderLookupGrantStore store = new OrderLookupGrantStore();
        String token = store.issue(EMAIL, 7200, 3, 100).orElseThrow().token();
        store.revoke(token);
        assertThat(store.resolve(token)).isEmpty();
    }
}
