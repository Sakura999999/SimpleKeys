package com.simplecard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplecard.config.*;
import com.simplecard.service.ProductQueryProtection;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductGuardFilterTest {
    @Test void invalidUuidIsLimitedThenRejectedBeforeControllerAndPermitIsReleased() throws Exception {
        ProductQueryProtection guard = mock(ProductQueryProtection.class);
        ClientIpResolver ip = mock(ClientIpResolver.class);
        when(guard.enabled()).thenReturn(true); when(guard.enterRequest()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products/not-a-uuid");
        request.setContextPath("/api"); when(ip.resolve(request)).thenReturn("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse(); MockFilterChain chain = new MockFilterChain();
        new ProductGuardFilter(guard, ip, new ObjectMapper()).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(400); assertThat(chain.getRequest()).isNull();
        verify(guard).admit("203.0.113.7"); verify(guard).exitRequest();
    }
    @Test void exhaustedAdmissionReturns429WithoutControllerAndReleasesPermit() throws Exception {
        ProductQueryProtection guard = mock(ProductQueryProtection.class);
        ClientIpResolver ip = mock(ClientIpResolver.class);
        when(guard.enabled()).thenReturn(true); when(guard.enterRequest()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products/00000000-0000-0000-0000-000000000001");
        request.setContextPath("/api"); when(ip.resolve(request)).thenReturn("peer");
        doThrow(new com.simplecard.exception.BusinessException(10005, "busy", org.springframework.http.HttpStatus.TOO_MANY_REQUESTS))
                .when(guard).admit("peer");
        MockHttpServletResponse response = new MockHttpServletResponse(); MockFilterChain chain = new MockFilterChain();
        new ProductGuardFilter(guard, ip, new ObjectMapper()).doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(429); assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(chain.getRequest()).isNull(); verify(guard).exitRequest();
    }
    @Test void paymentAndAdminRequestsBypassThisFilter() throws Exception {
        ProductQueryProtection guard = mock(ProductQueryProtection.class); when(guard.enabled()).thenReturn(true);
        for (String path : new String[]{"/api/payments/webhook/epay", "/api/admin/products"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path); request.setContextPath("/api");
            MockFilterChain chain = new MockFilterChain();
            new ProductGuardFilter(guard, mock(ClientIpResolver.class), new ObjectMapper())
                    .doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isEqualTo(request);
        }
        verify(guard, never()).enterRequest();
    }
}
