package com.wzx.babiq.server.application.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.protocol.ApplicationProtocolValidator;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.agent.TurnExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessDesktopAuthenticatedWebSocketIT {

    private static final String TOKEN = "A".repeat(43);
    private static final String INSTANCE_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ORIGIN = "http://127.0.0.1";
    private static final Path RUNTIME = Path.of(
            "target", "business-auth-it-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");

    static {
        try {
            Files.createDirectories(RUNTIME);
            Files.writeString(TOKEN_FILE, TOKEN, StandardCharsets.US_ASCII);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void businessProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessDesktopConnectionRegistry registry;

    @Autowired
    private ApplicationIdentityRegistry identities;

    @TestConfiguration
    static class MockConfig {

        @Bean
        @Primary
        TurnExecutor turnExecutor() {
            return mock(TurnExecutor.class);
        }

        @Bean
        @Primary
        ReActStrategy reActStrategy() {
            return mock(ReActStrategy.class);
        }
    }

    @Test
    void endpointRequiresAuthenticationPreventsDuplicatesAndAllowsReconnectAfterClose() throws Exception {
        assertThat(Files.notExists(TOKEN_FILE)).isTrue();
        assertRejected(null);
        assertRejected("wrong-token");

        List<String> received = new CopyOnWriteArrayList<>();
        WebSocketSession first = connect(TOKEN, INSTANCE_ID, SESSION_ID, received);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(registry.findByDesktopSessionId(SESSION_ID)).isPresent());

        assertThatThrownBy(() -> connect(TOKEN, INSTANCE_ID, SESSION_ID, new CopyOnWriteArrayList<>()))
                .isInstanceOf(Exception.class);

        first.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"no/such\",\"params\":{}}"));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(received).hasSize(1));
        JsonNode response = objectMapper.readTree(received.getFirst());
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32601);

        first.close();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(registry.findByDesktopSessionId(SESSION_ID)).isEmpty());

        try (WebSocketSession reconnected = connect(
                TOKEN, INSTANCE_ID, SESSION_ID, new CopyOnWriteArrayList<>())) {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(registry.findByDesktopSessionId(SESSION_ID)).isPresent());
        }
    }

    @Test
    void endpointAcceptsIdentityEnvelopeAboveTomcatDefaultWithinProtocolLimit() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(TOKEN, INSTANCE_ID, sessionId, received)) {
            var params = objectMapper.createObjectNode()
                    .put("protocolVersion", "1.0")
                    .put("desktopInstanceId", INSTANCE_ID)
                    .put("desktopSessionId", sessionId)
                    .put("authSessionId", "auth-large")
                    .put("identityEpoch", 1)
                    .put("sequence", 1)
                    .put("generatedAt", "2026-07-24T00:00:00Z")
                    .put("userId", "user-large")
                    .put("tenantId", "tenant-large")
                    .put("platformId", "2")
                    .put("authenticated", true);
            params.putArray("roles").add("tenant_admin");
            var permissions = params.putArray("permissions");
            for (int index = 0; index < 600; index++) {
                permissions.add("law:real-permission:" + index + ":query");
            }
            String request = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", 19)
                    .put("method", "application/identity/bind")
                    .set("params", params));
            byte[] requestBytes = request.getBytes(StandardCharsets.UTF_8);
            assertThat(requestBytes.length)
                    .isGreaterThan(8 * 1024)
                    .isLessThanOrEqualTo(ApplicationProtocolValidator.MAX_ENVELOPE_BYTES);

            session.sendMessage(new TextMessage(request));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(received.stream()
                            .map(this::read)
                            .anyMatch(response -> response.path("id").asInt() == 19
                                    && response.path("result").path("authenticated").asBoolean()
                                    && response.path("result").path("identityEpoch").asLong() == 1L))
                            .isTrue());
            var connection = registry.findByDesktopSessionId(sessionId).orElseThrow();
            assertThat(identities.current(connection)).get().satisfies(identity ->
                    assertThat(identity.permissions()).hasSize(600));
        }
    }

    private void assertRejected(String token) throws Exception {
        String rejectedSessionId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> connect(
                token, INSTANCE_ID, rejectedSessionId, new CopyOnWriteArrayList<>()))
                .isInstanceOf(Exception.class);
        assertThat(registry.findByDesktopSessionId(rejectedSessionId)).isEmpty();
        try (WebSocketSession accepted = connect(
                TOKEN, INSTANCE_ID, rejectedSessionId, new CopyOnWriteArrayList<>())) {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(registry.findByDesktopSessionId(rejectedSessionId)).isPresent());
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(registry.findByDesktopSessionId(rejectedSessionId)).isEmpty());
    }

    private WebSocketSession connect(
            String token,
            String instanceId,
            String sessionId,
            List<String> received) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (token != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        headers.set("X-Desktop-Instance-Id", instanceId);
        headers.set("X-Desktop-Session-Id", sessionId);
        headers.setOrigin(ORIGIN);
        return new StandardWebSocketClient().execute(
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        received.add(message.getPayload());
                    }
                },
                headers,
                URI.create("ws://127.0.0.1:" + port + "/ws/agent")).get();
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
