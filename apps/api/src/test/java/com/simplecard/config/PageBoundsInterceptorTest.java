package com.simplecard.config;

import com.simplecard.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 分页闸门测试。
 *
 * <p>要守住的边界只有三个：默认值不受影响、上限真的拦住、非法值返回 400 而不是 500。
 * 第三条最容易漏——{@code page=0} 在部分实现里会变成 {@code PageRequest.of(-1)} 抛
 * IllegalArgumentException，最终以 500 出去，看起来像服务端故障。
 */
class PageBoundsInterceptorTest {

    private final PageBoundsInterceptor interceptor = new PageBoundsInterceptor();

    private boolean pass(String page, String pageSize, String pageSizeCamel) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (page != null) request.setParameter("page", page);
        if (pageSize != null) request.setParameter("page_size", pageSize);
        if (pageSizeCamel != null) request.setParameter("pageSize", pageSizeCamel);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void allowsNormalValues() {
        assertDoesNotThrow(() -> pass("1", "20", null));
        assertDoesNotThrow(() -> pass("10000", "100", null));
        assertDoesNotThrow(() -> pass("1", null, "50"));
    }

    /** 不带分页参数的接口（如下单、回调）必须原样放行 */
    @Test
    void allowsRequestsWithoutPagination() {
        assertDoesNotThrow(() -> pass(null, null, null));
        assertDoesNotThrow(() -> pass("", "", null));
    }

    @Test
    void rejectsZeroOrNegativePage() {
        assertThrows(BusinessException.class, () -> pass("0", "20", null));
        assertThrows(BusinessException.class, () -> pass("-1", "20", null));
    }

    @Test
    void rejectsOversizedPageSize() {
        assertThrows(BusinessException.class, () -> pass("1", "101", null));
        assertThrows(BusinessException.class, () -> pass("1", "100000", null));
        // 驼峰写法同样要拦，不能因为参数名不同就绕过
        assertThrows(BusinessException.class, () -> pass("1", null, "5000"));
    }

    @Test
    void rejectsNonNumeric() {
        assertThrows(BusinessException.class, () -> pass("abc", "20", null));
        assertThrows(BusinessException.class, () -> pass("1", "1e9", null));
    }

    /** 空白值等同于没传，不能因此拒掉正常请求 */
    @Test
    void treatsBlankAsAbsent() {
        assertDoesNotThrow(() -> pass("1", " ", null));
    }
}
