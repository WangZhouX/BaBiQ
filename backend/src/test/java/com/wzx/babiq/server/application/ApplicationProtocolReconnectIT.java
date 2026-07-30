package com.wzx.babiq.server.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.application.action.SQLiteApplicationActionTerminalStore;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationProtocolReconnectIT {

    private static final String TOKEN = "R".repeat(43);
    private static final String INSTANCE_ID = "41111111-1111-4111-8111-111111111111";
    private static final String SESSION_ID = "42222222-2222-4222-8222-222222222222";
    private static final String NEW_SESSION_ID = "43333333-3333-4333-8333-333333333333";
    private static final Path RUNTIME = Path.of(
            "target", "application-reconnect-it-" + UUID.randomUUID()).toAbsolutePath().normalize();
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
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
        registry.add("babiq.business.legacy-client-projections-enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ToolCallPersistenceService toolCalls;

    @Autowired
    private com.wzx.babiq.server.application.action.PendingApplicationActions actions;

    @Autowired
    private BusinessDesktopConnectionRegistry connections;

    @Autowired
    private SQLiteApplicationActionTerminalStore terminalStore;

    @TestConfiguration
    static class ModelIsolation {
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
    void reconnectMustRepublishStateAndCanQueryOnlyThePersistedOldSessionAction() throws Exception {
        String threadId;
        String turnId;
        String toolCallId = "tool-reconnect-" + UUID.randomUUID();
        String executionId = "execution-reconnect-" + UUID.randomUUID();
        var correlationHolder = new java.util.concurrent.atomic.AtomicReference<
                com.wzx.babiq.server.application.action.PendingApplicationAction.Correlation>();
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession first = connect(SESSION_ID, received)) {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(connections.findByDesktopSessionId(SESSION_ID)).isPresent());
            request(first, received, 1, "application/identity/bind", identity(SESSION_ID));
            request(first, received, 2, "application/catalog/register", catalog(SESSION_ID));
            request(first, received, 3, "application/context/publish", pageContext(SESSION_ID));
            threadId = request(first, received, 4, "thread/create",
                    json.createObjectNode().put("cwd", RUNTIME.toString())).path("threadId").asText();
            BusinessIdentityScope scope = scope(SESSION_ID);
            var turn = conversations.startTurn(threadId, scope);
            turn.start();
            turnId = turn.id();
            conversations.persistTurnStarted(turn, "reconnect", "dashscope-default", "qwen-plus",
                    RUNTIME.toString(), "READ_ONLY", "NEVER");
            toolCalls.recordStarted(toolCallId, threadId, turnId, "application_action", "{}",
                    "babiq_agent", null, null, scope, Instant.now());
            var correlation = new com.wzx.babiq.server.application.action.PendingApplicationAction.Correlation(
                    threadId, turnId, toolCallId);
            correlationHolder.set(correlation);
            var context = connectionContext(
                    connections.findByDesktopSessionId(SESSION_ID).orElseThrow());
            actions.register(executionId, correlation,
                    com.wzx.babiq.server.application.action.PendingApplicationAction.Path.READ_ONLY,
                    context,
                    new com.wzx.babiq.server.application.action.PendingApplicationActions.RegistrationMetadata(
                            "case.read", 1, "sha256:" + UUID.randomUUID()),
                    null);
            toolCalls.bindExecutionId(turnId, toolCallId, scope, executionId);
            actions.acceptedAuthorized(executionId, correlation, context);
            actions.runningAuthorized(executionId, correlation, context);
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(connections.findByDesktopSessionId(SESSION_ID)).isEmpty());

        List<String> sameReceived = new CopyOnWriteArrayList<>();
        try (WebSocketSession sameSession = connect(SESSION_ID, sameReceived)) {
            JsonNode deniedBeforeRepublish = requestResponse(sameSession, sameReceived, 10,
                    "application/action/status", actionMessage(SESSION_ID, correlationHolder.get(), executionId, "status_query"));
            assertThat(deniedBeforeRepublish.path("error").path("code").asInt()).isEqualTo(-32601);

            request(sameSession, sameReceived, 11, "application/identity/bind", identity(SESSION_ID));
            JsonNode reconciliationStatus = awaitOutbound(sameReceived, "application/action/status");
            JsonNode reconciliationParams = reconciliationStatus.path("params");
            assertThat(reconciliationParams.path("executionId").asText()).isEqualTo(executionId);
            assertThat(reconciliationParams.path("desktopSessionId").asText()).isEqualTo(SESSION_ID);
            send(sameSession, response(reconciliationStatus.path("id").asLong(),
                    json.createObjectNode().put("executionId", executionId).put("state", "succeeded")));
            JsonNode resultQuery = awaitOutbound(sameReceived, "application/action/result/get");
            send(sameSession, response(resultQuery.path("id").asLong(),
                    json.createObjectNode().put("executionId", executionId).put("state", "succeeded")
                            .put("output", "persisted desktop result")));
            request(sameSession, sameReceived, 12, "application/catalog/register", catalog(SESSION_ID));
            request(sameSession, sameReceived, 13, "application/context/publish", pageContext(SESSION_ID));
            JsonNode status = request(sameSession, sameReceived, 14, "application/action/status",
                    actionMessage(SESSION_ID, correlationHolder.get(), executionId, "status_query"));
            assertThat(status.path("executionId").asText()).isEqualTo(executionId);
            assertThat(status.path("state").asText()).isEqualTo("outcome_unknown");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(terminalStore.events(executionId, connectionContext(
                            connections.findByDesktopSessionId(SESSION_ID).orElseThrow())))
                            .anySatisfy(event -> {
                                assertThat(event.getEventType()).isEqualTo("late_result");
                                assertThat(event.getToStatus()).isEqualTo("COMPLETED");
                                assertThat(event.getLateResult()).isTrue();
                            }));
        }

        List<String> newReceived = new CopyOnWriteArrayList<>();
        try (WebSocketSession newSession = connect(NEW_SESSION_ID, newReceived)) {
            request(newSession, newReceived, 20, "application/identity/bind", identity(NEW_SESSION_ID));
            request(newSession, newReceived, 21, "application/catalog/register", catalog(NEW_SESSION_ID));
            request(newSession, newReceived, 22, "application/context/publish", pageContext(NEW_SESSION_ID));
            JsonNode denied = requestResponse(newSession, newReceived, 23, "application/action/status",
                    actionMessage(NEW_SESSION_ID, correlationHolder.get(), executionId, "status_query"));
            assertThat(denied.path("error").path("code").asInt()).isEqualTo(-32602);
        }
    }

    private WebSocketSession connect(String desktopSessionId, List<String> received) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        headers.set("X-Desktop-Instance-Id", INSTANCE_ID);
        headers.set("X-Desktop-Session-Id", desktopSessionId);
        headers.setOrigin("http://127.0.0.1");
        WebSocketSession session = new StandardWebSocketClient().execute(
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        received.add(message.getPayload());
                    }
                }, headers, URI.create("ws://127.0.0.1:" + port + "/ws/agent")).get();
        return session;
    }

    private JsonNode request(WebSocketSession session, List<String> received, long id, String method, JsonNode params)
            throws Exception {
        JsonNode response = requestResponse(session, received, id, method, params);
        assertThat(response.path("error").isMissingNode()).as(response.toString()).isTrue();
        return response.path("result");
    }

    private JsonNode requestResponse(
            WebSocketSession session, List<String> received, long id, String method, JsonNode params) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(json.createObjectNode()
                .put("jsonrpc", "2.0").put("id", id).put("method", method).set("params", params))));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(received.stream().map(this::read).anyMatch(node -> node.path("id").asLong() == id)).isTrue());
        return received.stream().map(this::read)
                .filter(node -> node.path("id").asLong() == id).findFirst().orElseThrow();
    }

    private JsonNode awaitOutbound(List<String> received, String method) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(received.stream().map(this::read)
                        .anyMatch(node -> method.equals(node.path("method").asText()))).isTrue());
        return received.stream().map(this::read)
                .filter(node -> method.equals(node.path("method").asText()))
                .findFirst().orElseThrow();
    }

    private void send(WebSocketSession session, JsonNode message) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(message)));
    }

    private JsonNode response(long id, JsonNode result) {
        return json.createObjectNode().put("jsonrpc", "2.0").put("id", id).set("result", result);
    }

    private JsonNode identity(String desktopSessionId) {
        var result = envelope(desktopSessionId, 1)
                .put("authSessionId", "auth-1").put("identityEpoch", 1)
                .put("userId", "user-1").put("tenantId", "tenant-1").put("platformId", "platform-1")
                .put("authenticated", true);
        result.putArray("roles").add("lawyer");
        result.putArray("permissions").add("case:read");
        return result;
    }

    private JsonNode catalog(String desktopSessionId) {
        var payload = json.createObjectNode();
        payload.putObject("actions").putObject("case.read")
                .put("id", "case.read").put("version", 1).put("risk", "read_only").put("enabled", true)
                .putArray("requiredPermissions").add("case:read");
        return scopedEnvelope(desktopSessionId, 2, payload);
    }

    private JsonNode pageContext(String desktopSessionId) {
        return scopedEnvelope(desktopSessionId, 3,
                json.createObjectNode().put("pageId", "case-page").put("contextRevision", 1));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode scopedEnvelope(
            String desktopSessionId, long sequence, JsonNode payload) {
        return envelope(desktopSessionId, sequence)
                .put("authSessionId", "auth-1").put("identityEpoch", 1)
                .put("userId", "user-1").put("tenantId", "tenant-1").put("platformId", "platform-1")
                .put("catalogEpoch", 1).put("contextSequence", sequence)
                .put("payloadSize", payload.toString().getBytes(StandardCharsets.UTF_8).length)
                .set("payload", payload);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode actionMessage(
            String desktopSessionId,
            com.wzx.babiq.server.application.action.PendingApplicationAction.Correlation correlation,
            String executionId,
            String state) {
        return scopedEnvelope(desktopSessionId, 10,
                json.createObjectNode().put("state", state))
                .put("threadId", correlation.threadId()).put("turnId", correlation.turnId())
                .put("toolCallId", correlation.toolCallId()).put("executionId", executionId);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode envelope(String desktopSessionId, long sequence) {
        return json.createObjectNode().put("protocolVersion", "1.0")
                .put("desktopInstanceId", INSTANCE_ID).put("desktopSessionId", desktopSessionId)
                .put("sequence", sequence).put("generatedAt", "2026-07-18T00:00:00Z");
    }

    private BusinessIdentityScope scope(String desktopSessionId) {
        return BusinessIdentityScope.scoped(
                INSTANCE_ID, desktopSessionId, "auth-1", 1, "user-1", "tenant-1", "platform-1");
    }

    private com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext connectionContext(
            TrustedDesktopConnection connection) {
        return new com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext(
                connection.reservationId(), connection.webSocketSessionId(),
                connection.desktopInstanceId(), connection.desktopSessionId(),
                "auth-1", 1, "user-1", "tenant-1", "platform-1");
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
