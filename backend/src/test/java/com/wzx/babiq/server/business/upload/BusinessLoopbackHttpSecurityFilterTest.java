package com.wzx.babiq.server.business.upload;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLoopbackHttpSecurityFilterTest {
    @Test
    void origin_and_host_must_match_current_port_and_any_forwarding_header_is_rejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/business/attachments/uploads/b");
        request.setLocalPort(43123);
        request.addHeader("Host", "127.0.0.1:43123");
        request.addHeader("Origin", "http://127.0.0.1:43123");
        assertThat(BusinessLoopbackHttpSecurityFilter.hasUnsafeForwardingHeader(request)).isFalse();
        assertThat(BusinessLoopbackHttpSecurityFilter.isExactOrigin(
                request.getHeader("Origin"), request.getHeader("Host"), request.getLocalPort(),
                Set.of("http://127.0.0.1:43123"))).isTrue();
        assertThat(BusinessLoopbackHttpSecurityFilter.isExactOrigin(
                request.getHeader("Origin"), request.getHeader("Host"), request.getLocalPort(),
                Set.of("http://127.0.0.1"))).isTrue();

        request.addHeader("X-Forwarded-Port", "443");
        assertThat(BusinessLoopbackHttpSecurityFilter.hasUnsafeForwardingHeader(request)).isTrue();
        assertThat(BusinessLoopbackHttpSecurityFilter.isExactOrigin(
                "http://127.0.0.1:9999", "127.0.0.1:43123", 43123,
                Set.of("http://127.0.0.1:43123"))).isFalse();
    }

    @Test
    void ipv6_loopback_origin_is_not_supported() {
        assertThat(BusinessLoopbackHttpSecurityFilter.isExactOrigin(
                "http://[::1]:43123", "[::1]:43123", 43123,
                Set.of("http://[::1]"))).isFalse();
    }

    @Test
    void rejection_uses_a_fresh_local_correlation_id() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        BusinessLoopbackHttpSecurityFilter.reject(first, "BUSINESS_RESOURCE_UNAVAILABLE");
        BusinessLoopbackHttpSecurityFilter.reject(second, "BUSINESS_RESOURCE_UNAVAILABLE");

        String firstId = first.getContentAsString().replaceAll(".*correlationId\":\"([^\"]+)\".*", "$1");
        String secondId = second.getContentAsString().replaceAll(".*correlationId\":\"([^\"]+)\".*", "$1");
        assertThat(UUID.fromString(firstId)).isNotNull();
        assertThat(secondId).isNotEqualTo(firstId);
    }
}
