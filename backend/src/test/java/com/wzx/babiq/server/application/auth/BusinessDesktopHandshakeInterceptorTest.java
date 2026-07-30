package com.wzx.babiq.server.application.auth;

import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.recovery.StartupRecoveryCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessDesktopHandshakeInterceptorTest {

    @Test
    void every_handshake_constructor_requires_the_startup_recovery_coordinator() {
        assertThat(Arrays.stream(BusinessDesktopHandshakeInterceptor.class.getConstructors()))
                .isNotEmpty();
        assertThat(Arrays.stream(BusinessDesktopHandshakeInterceptor.class.getDeclaredConstructors()))
                .allMatch(constructor -> Arrays.asList(constructor.getParameterTypes())
                        .contains(StartupRecoveryCoordinator.class));
    }

    @Test
    void spring_wires_the_production_handshake_to_the_shared_recovery_gate() throws Exception {
        Path tokenFile = tempDir.resolve("spring-wiring-session-token");
        Files.writeString(tokenFile, TOKEN, StandardCharsets.US_ASCII);
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();

        new ApplicationContextRunner()
                .withPropertyValues("babiq.business.enabled=true")
                .withUserConfiguration(HandshakeWiringConfiguration.class)
                .withBean(BusinessDesktopModeProperties.class, this::properties)
                .withBean(DesktopSessionTokenProvider.class,
                        () -> new DesktopSessionTokenProvider(tokenFile))
                .withBean(BusinessDesktopConnectionRegistry.class, () -> registry)
                .withBean(StartupRecoveryCoordinator.class, StartupRecoveryCoordinator::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BusinessDesktopHandshakeInterceptor interceptor =
                            context.getBean(BusinessDesktopHandshakeInterceptor.class);
                    StartupRecoveryCoordinator recovery =
                            context.getBean(StartupRecoveryCoordinator.class);
                    assertThat(ReflectionTestUtils.getField(
                            interceptor, "startupRecoveryCoordinator")).isSameAs(recovery);

                    ServerHttpRequest legalRequest = request(
                            TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1");
                    ServerHttpResponse blockedResponse = mock(ServerHttpResponse.class);
                    Map<String, Object> attributes = new HashMap<>();

                    assertThat(interceptor.beforeHandshake(
                            legalRequest,
                            blockedResponse,
                            mock(WebSocketHandler.class),
                            attributes)).isFalse();
                    verify(blockedResponse).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(attributes).isEmpty();
                    String probeReservation = registry.reserve(INSTANCE_ID, SESSION_ID);
                    assertThat(registry.cancelReservation(probeReservation)).isTrue();

                    recovery.markRecoveryComplete();

                    assertThat(interceptor.beforeHandshake(
                            legalRequest,
                            mock(ServerHttpResponse.class),
                            mock(WebSocketHandler.class),
                            attributes)).isTrue();
                    assertThat(attributes)
                            .containsEntry(
                                    BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE,
                                    INSTANCE_ID)
                            .containsEntry(
                                    BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE,
                                    SESSION_ID)
                            .containsKey(
                                    BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE);
                });
    }

    @Test
    void valid_handshake_waits_for_startup_recovery_without_creating_a_reservation() throws Exception {
        StartupRecoveryCoordinator recovery = new StartupRecoveryCoordinator();
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry();
        Fixture fixture = fixture(
                registry,
                recovery,
                Clock.systemUTC(),
                Duration.ofSeconds(10),
                1024);
        Map<String, Object> blockedAttributes = new HashMap<>();

        assertThat(fixture.interceptor.beforeHandshake(
                request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                fixture.response,
                mock(WebSocketHandler.class),
                blockedAttributes)).isFalse();
        verify(fixture.response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(blockedAttributes).isEmpty();
        String probeReservation = registry.reserve(INSTANCE_ID, SESSION_ID);
        assertThat(probeReservation).isNotBlank();
        assertThat(registry.cancelReservation(probeReservation)).isTrue();

        recovery.markRecoveryComplete();

        assertThat(fixture.interceptor.beforeHandshake(
                request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                new HashMap<>())).isTrue();
    }

    private static final String TOKEN = "A".repeat(43);
    private static final String INSTANCE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ORIGIN = "http://127.0.0.1";

    @TempDir
    Path tempDir;

    @Test
    void authenticatesLoopbackHeadersAndStoresOnlyTrustedIdsAndReservation() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = fixture.interceptor.beforeHandshake(
                request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                fixture.response,
                mock(WebSocketHandler.class),
                attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes)
                .containsEntry(BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, INSTANCE_ID)
                .containsEntry(BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, SESSION_ID)
                .containsKey(BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE)
                .doesNotContainValue(TOKEN);
        assertThat(attributes).hasSize(3);
        assertThat(fixture.registry.findByDesktopSessionId(SESSION_ID)).isEmpty();
    }

    @Test
    void rejectsMissingOrWrongBearerTokens() throws Exception {
        Fixture fixture = fixture();

        assertThat(fixture.interceptor.beforeHandshake(
                request(null, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                fixture.response,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();
        verify(fixture.response).setStatusCode(HttpStatus.UNAUTHORIZED);

        ServerHttpResponse wrongResponse = mock(ServerHttpResponse.class);
        assertThat(fixture.interceptor.beforeHandshake(
                request("wrong-token", INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                wrongResponse,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();
        verify(wrongResponse).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsMalformedIdsNonLoopbackPeersAndUnpermittedOrigins() throws Exception {
        Fixture fixture = fixture();

        assertRejected(fixture, request(TOKEN, "not-a-uuid", SESSION_ID, ORIGIN, "127.0.0.1"));
        assertRejected(fixture, request(TOKEN, INSTANCE_ID, "not-a-uuid", ORIGIN, "127.0.0.1"));
        assertRejected(fixture, request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "192.0.2.10"));
        assertRejected(fixture, request(TOKEN, INSTANCE_ID, SESSION_ID, "https://attacker.example", "127.0.0.1"));
    }

    @Test
    void rejectsDuplicateSessionsAndReleasesReservationWhenHandshakeFails() throws Exception {
        Fixture fixture = fixture();
        ServerHttpRequest request = request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1");
        Map<String, Object> firstAttributes = new HashMap<>();

        assertThat(fixture.interceptor.beforeHandshake(
                request, fixture.response, mock(WebSocketHandler.class), firstAttributes)).isTrue();

        ServerHttpResponse duplicateResponse = mock(ServerHttpResponse.class);
        assertThat(fixture.interceptor.beforeHandshake(
                request, duplicateResponse, mock(WebSocketHandler.class), new HashMap<>())).isFalse();
        verify(duplicateResponse).setStatusCode(HttpStatus.CONFLICT);

        fixture.interceptor.afterHandshake(
                request, fixture.response, mock(WebSocketHandler.class), new IllegalStateException("upgrade failed"));
        Map<String, Object> retriedAttributes = new HashMap<>();
        assertThat(fixture.interceptor.beforeHandshake(
                request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), retriedAttributes)).isTrue();
    }

    @Test
    void delayedFailureCallbackCannotCancelANewerReservationForTheSameIdentity() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T00:00:00Z"));
        BusinessDesktopConnectionRegistry registry =
                new BusinessDesktopConnectionRegistry(clock, Duration.ofSeconds(10));
        Fixture fixture = fixture(registry, clock, Duration.ofSeconds(10), 1024);
        ServerHttpRequest oldRequest = request(
                TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1");
        Map<String, Object> oldAttributes = new HashMap<>();
        assertThat(fixture.interceptor.beforeHandshake(
                oldRequest, fixture.response, mock(WebSocketHandler.class), oldAttributes)).isTrue();

        clock.advance(Duration.ofSeconds(10));
        ServerHttpRequest newRequest = request(
                TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1");
        Map<String, Object> newAttributes = new HashMap<>();
        assertThat(fixture.interceptor.beforeHandshake(
                newRequest, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), newAttributes)).isTrue();

        fixture.interceptor.afterHandshake(
                oldRequest,
                fixture.response,
                mock(WebSocketHandler.class),
                new IllegalStateException("delayed upgrade failure"));

        assertThat(fixture.registry.finalizeReservation(
                (String) newAttributes.get(BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE),
                INSTANCE_ID,
                SESSION_ID,
                "ws-new").webSocketSessionId()).isEqualTo("ws-new");
    }

    @Test
    void missingAfterHandshakeCallbackDoesNotBlockReconnectAfterReservationTtl() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T00:00:00Z"));
        Fixture fixture = fixture(
                new BusinessDesktopConnectionRegistry(clock, Duration.ofSeconds(10)),
                clock,
                Duration.ofSeconds(10),
                1024);
        ServerHttpRequest firstRequest = request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1");

        assertThat(fixture.interceptor.beforeHandshake(
                firstRequest, fixture.response, mock(WebSocketHandler.class), new HashMap<>())).isTrue();
        clock.advance(Duration.ofSeconds(10));

        assertThat(fixture.interceptor.beforeHandshake(
                request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                mock(ServerHttpResponse.class),
                mock(WebSocketHandler.class),
                new HashMap<>())).isTrue();
    }

    @Test
    void requestLeaseCapacityIsBoundedAndRejectedReservationIsCancelled() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T00:00:00Z"));
        BusinessDesktopConnectionRegistry registry =
                new BusinessDesktopConnectionRegistry(clock, Duration.ofSeconds(10));
        Fixture fixture = fixture(registry, clock, Duration.ofSeconds(10), 1);
        assertThat(fixture.interceptor.beforeHandshake(
                request(TOKEN, INSTANCE_ID, SESSION_ID, ORIGIN, "127.0.0.1"),
                fixture.response,
                mock(WebSocketHandler.class),
                new HashMap<>())).isTrue();
        String secondSessionId = "33333333-3333-4333-8333-333333333333";
        ServerHttpResponse capacityResponse = mock(ServerHttpResponse.class);

        assertThat(fixture.interceptor.beforeHandshake(
                request(TOKEN, INSTANCE_ID, secondSessionId, ORIGIN, "127.0.0.1"),
                capacityResponse,
                mock(WebSocketHandler.class),
                new HashMap<>())).isFalse();

        verify(capacityResponse).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(registry.reserve(INSTANCE_ID, secondSessionId)).isNotBlank();
    }

    private void assertRejected(Fixture fixture, ServerHttpRequest request) throws Exception {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        assertThat(fixture.interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>())).isFalse();
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    private Fixture fixture() throws Exception {
        return fixture(new BusinessDesktopConnectionRegistry());
    }

    private Fixture fixture(BusinessDesktopConnectionRegistry registry) throws Exception {
        return fixture(registry, Clock.systemUTC(), Duration.ofSeconds(10), 1024);
    }

    private Fixture fixture(
            BusinessDesktopConnectionRegistry registry,
            Clock clock,
            Duration requestLeaseTtl,
            int maxRequestLeases) throws Exception {
        StartupRecoveryCoordinator recovery = new StartupRecoveryCoordinator();
        recovery.markRecoveryComplete();
        return fixture(registry, recovery, clock, requestLeaseTtl, maxRequestLeases);
    }

    private Fixture fixture(
            BusinessDesktopConnectionRegistry registry,
            StartupRecoveryCoordinator recovery,
            Clock clock,
            Duration requestLeaseTtl,
            int maxRequestLeases) throws Exception {
        Path tokenFile = tempDir.resolve("session-token-" + System.nanoTime());
        Files.writeString(tokenFile, TOKEN, StandardCharsets.US_ASCII);
        DesktopSessionTokenProvider tokenProvider = new DesktopSessionTokenProvider(tokenFile);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        return new Fixture(
                new BusinessDesktopHandshakeInterceptor(
                        tokenProvider,
                        registry,
                        properties(),
                        recovery,
                        clock,
                        requestLeaseTtl,
                        maxRequestLeases),
                registry,
                response);
    }

    private ServerHttpRequest request(
            String token,
            String instanceId,
            String sessionId,
            String origin,
            String remoteAddress) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        headers.set("X-Desktop-Instance-Id", instanceId);
        headers.set("X-Desktop-Session-Id", sessionId);
        headers.set(HttpHeaders.ORIGIN, origin);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(
                InetAddress.getByName(remoteAddress), 43117));
        return request;
    }

    private BusinessDesktopModeProperties properties() {
        Path runtime = tempDir.toAbsolutePath().normalize();
        return new BusinessDesktopModeProperties(
                true,
                runtime,
                runtime.resolve("data/business.db"),
                runtime.resolve("secrets/business.jceks"),
                runtime.resolve("logs/backend.log"),
                runtime.resolve("memory"),
                runtime.resolve("teams"),
                runtime.resolve("instance.lock"),
                runtime.resolve("session-token"),
                true,
                "127.0.0.1",
                ORIGIN,
                256 * 1024,
                128 * 1024,
                128 * 1024,
                64 * 1024,
                64 * 1024,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(5),
                Duration.ofMinutes(2),
                Duration.ofSeconds(10));
    }

    private record Fixture(
            BusinessDesktopHandshakeInterceptor interceptor,
            BusinessDesktopConnectionRegistry registry,
            ServerHttpResponse response
    ) {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BusinessDesktopHandshakeInterceptor.class)
    static class HandshakeWiringConfiguration {
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
