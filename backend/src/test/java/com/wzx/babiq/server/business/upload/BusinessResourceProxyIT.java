package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.DesktopSessionTokenProvider;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessResourceProxyIT {
    private static final AtomicReference<ReadyOaSessionLease> CURRENT_LEASE =
            new AtomicReference<>(lease(1));
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-07-29T00:00:00Z"));

    @Test
    void true_loopback_http_returns_only_bound_opaque_handle_with_no_store_headers() throws Exception {
        ConfigurableApplicationContext context = new SpringApplication(ResourceHttpTestApplication.class)
                .run("--server.address=127.0.0.1", "--server.port=0",
                        "--babiq.business.enabled=true",
                        "--babiq.business.allowed-origins=http://127.0.0.1");
        try {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var registry = context.getBean(BusinessResourceHandleRegistry.class);
            CURRENT_LEASE.set(lease(1));
            CLOCK.set(Instant.parse("2026-07-29T00:00:00Z"));
            var descriptor = registry.register(connection(), lease(1), "image/png",
                    new byte[]{1, 2, 3}, Duration.ofMinutes(1));
            HttpResponse<byte[]> response = get(port, descriptor.handle());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).containsExactly(1, 2, 3);
            assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
            assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                    .contains("no-store", "must-revalidate");

            CURRENT_LEASE.set(lease(2));
            assertUnavailable(get(port, descriptor.handle()));

            CURRENT_LEASE.set(lease(1));
            var revoked = registry.register(connection(), lease(1), "image/png",
                    new byte[]{4}, Duration.ofMinutes(1));
            registry.revoke(connection(), lease(1));
            assertUnavailable(get(port, revoked.handle()));

            CURRENT_LEASE.set(lease(2));
            var expired = registry.register(connection(), lease(2), "image/png",
                    new byte[]{5}, Duration.ofSeconds(1));
            CLOCK.set(Instant.parse("2026-07-29T00:00:02Z"));
            assertUnavailable(get(port, expired.handle()));
        } finally {
            context.close();
        }
    }

    private static HttpResponse<byte[]> get(int port, String handle) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/business/resources/" + handle))
                .header("Origin", "http://127.0.0.1:" + port)
                .header("Authorization", "Bearer desktop-token")
                .header("X-Desktop-Instance-Id", "instance-1")
                .header("X-Desktop-Session-Id", "desktop-1")
                .GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertUnavailable(HttpResponse<byte[]> response) {
        String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Business-Code"))
                .contains("BUSINESS_RESOURCE_UNAVAILABLE");
        assertThat(body).contains("\"businessCode\":\"BUSINESS_RESOURCE_UNAVAILABLE\"")
                .contains("\"correlationId\":");
    }

    private static TrustedDesktopConnection connection() {
        return new TrustedDesktopConnection("reservation-1", "instance-1", "desktop-1", "ws-1");
    }

    private static ReadyOaSessionLease lease(long generation) {
        return new ReadyOaSessionLease("auth-1", "instance-1", "desktop-1", "ws-1",
                "user-1", "tenant-1", "2", generation, "credential-" + generation, 1, Instant.now());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration.class})
    @Import({BusinessLoopbackHttpSecurityFilter.class, BusinessResourceProxyController.class,
            BusinessUploadExceptionHandler.class})
    static class ResourceHttpTestApplication {
        @Bean BusinessAttachmentTicketService tickets() { return new BusinessAttachmentTicketService(); }
        @Bean BusinessResourceHandleRegistry resources() {
            return new BusinessResourceHandleRegistry(CLOCK, Duration.ofMinutes(5));
        }
        @Bean DesktopSessionTokenProvider tokenProvider() {
            var provider = mock(DesktopSessionTokenProvider.class);
            when(provider.matches("desktop-token")).thenReturn(true);
            return provider;
        }
        @Bean BusinessDesktopConnectionRegistry connections() {
            var registry = mock(BusinessDesktopConnectionRegistry.class);
            when(registry.findByDesktopSessionId("desktop-1"))
                    .thenReturn(java.util.Optional.of(connection()));
            return registry;
        }
        @Bean BusinessOaSessionRegistry sessions() {
            var registry = mock(BusinessOaSessionRegistry.class);
            when(registry.captureReady(connection())).thenAnswer(ignored -> CURRENT_LEASE.get());
            return registry;
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
