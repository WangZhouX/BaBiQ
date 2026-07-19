package com.wzx.babiq.server.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.tool.ApplicationToolInvocationContext;
import com.wzx.babiq.server.conversation.ConversationEventRecorder;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.tool.ToolRegistry;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationBridgeEndToEndIT {

    private static final String TOKEN = "B".repeat(43);
    private static final String INSTANCE_ID = "31111111-1111-4111-8111-111111111111";
    private static final String DESKTOP_SESSION_ID = "32222222-2222-4222-8222-222222222222";
    private static final Path RUNTIME = Path.of(
            "target", "application-bridge-it-" + UUID.randomUUID()).toAbsolutePath().normalize();
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
    static void businessRuntime(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private BusinessDesktopConnectionRegistry connections;

    @Autowired
    private ApplicationIdentityRegistry identities;

    @Autowired
    private ApplicationCatalogRegistry catalogs;

    @Autowired
    private ApplicationPageContextRegistry contexts;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEventRecorder recorder;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ToolCallPersistenceService toolCalls;

    @Autowired
    private ToolRegistry tools;

    @Autowired private PendingApprovals approvals;
    @Autowired private AgentLoop agentLoop;

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
    void closingAuthenticatedBridgeInvalidatesConnectionLocalIdentityCatalogAndContext() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        WebSocketSession session = connect(received);
        request(session, received, 1, "application/identity/bind", identity());
        request(session, received, 2, "application/catalog/register", catalog());
        request(session, received, 3, "application/context/publish", pageContext());

        TrustedDesktopConnection connection = connections.findByDesktopSessionId(DESKTOP_SESSION_ID).orElseThrow();
        AtomicInteger closeEvents = new AtomicInteger();
        connections.addCloseListener((closed, reason) -> {
            if (closed.equals(connection)) {
                closeEvents.incrementAndGet();
            }
        });
        assertThat(identities.current(connection)).isPresent();
        assertThat(catalogs.current(connection)).isPresent();
        assertThat(contexts.current(connection)).isPresent();

        session.close();

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(connections.findByDesktopSessionId(DESKTOP_SESSION_ID)).isEmpty();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();
            assertThat(closeEvents).hasValue(1);
        });
    }

    @Test
    void realIdentityUpdateCommitsNewIdentityAndExpiresOnlyOldPreExecutionWork() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(received)) {
            request(session, received, 5, "application/identity/bind", identity());
            JsonNode threadJson = request(session, received, 6, "thread/create",
                    json.createObjectNode().put("cwd", RUNTIME.toString()));
            String threadId = threadJson.path("threadId").asText();
            BusinessIdentityScope oldScope = scope();
            var created = conversations.startTurn(threadId, oldScope);
            conversations.persistTurnStarted(created, "created", "dashscope-default", "qwen-plus",
                    RUNTIME.toString(), "READ_ONLY", "NEVER");
            var waiting = conversations.startTurn(threadId, oldScope);
            waiting.start();
            waiting.waitApproval();
            conversations.persistTurnStarted(waiting, "waiting", "dashscope-default", "qwen-plus",
                    RUNTIME.toString(), "READ_ONLY", "NEVER");
            var running = conversations.startTurn(threadId, oldScope);
            running.start();
            conversations.persistTurnStarted(running, "running", "dashscope-default", "qwen-plus",
                    RUNTIME.toString(), "READ_ONLY", "NEVER");
            approvals.put(threadId, org.mockito.Mockito.mock(
                    com.alibaba.cloud.ai.graph.action.InterruptionMetadata.class));
            Object outputHandler = ReflectionTestUtils.getField(agentLoop, "outputHandler");
            assertThat(outputHandler).isNotNull();
            Object pausedAgents = ReflectionTestUtils.getField(outputHandler, "pausedAgents");
            assertThat(pausedAgents).isNotNull();
            ReflectionTestUtils.invokeMethod(pausedAgents, "remember", threadId,
                    mock(com.alibaba.cloud.ai.graph.agent.ReactAgent.class));
            TrustedDesktopConnection connection = connections.findByDesktopSessionId(DESKTOP_SESSION_ID).orElseThrow();
            var oldIdentity = identities.current(connection).orElseThrow();
            var actionScope = new com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext(
                    connection.reservationId(), connection.webSocketSessionId(), connection.desktopInstanceId(),
                    connection.desktopSessionId(), oldIdentity.authSessionId(), oldIdentity.identityEpoch(),
                    oldIdentity.userId(), oldIdentity.tenantId(), oldIdentity.platformId());
            String pendingExecutionId = "execution-identity-update-" + UUID.randomUUID();
            String pendingToolCallId = "tool-identity-update-" + UUID.randomUUID();
            toolCalls.recordStarted(pendingToolCallId, threadId, created.id(), "application_action", "{}",
                    "babiq_agent", null, null, oldScope, java.time.Instant.now());
            var pendingCorrelation = new com.wzx.babiq.server.application.action.PendingApplicationAction.Correlation(
                    threadId, created.id(), pendingToolCallId);
            var pendingTerminal = pendingActions().register(
                    pendingExecutionId, pendingCorrelation,
                    com.wzx.babiq.server.application.action.PendingApplicationAction.Path.READ_ONLY,
                    actionScope);

            JsonNode result = request(session, received, 7, "application/identity/update", identityUpdate());

            assertThat(result.path("identityEpoch").asLong()).isEqualTo(2);
            assertThat(identities.current(connection)).get().satisfies(identity -> {
                assertThat(identity.identityEpoch()).isEqualTo(2);
                assertThat(identity.authSessionId()).isEqualTo("auth-2");
            });
            assertThat(created.status()).isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.EXPIRED);
            assertThat(waiting.status()).isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.EXPIRED);
            assertThat(running.status()).isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.RUNNING);
            assertThat(approvals.peek(threadId)).isNull();
            Object remainingPausedAgent = ReflectionTestUtils.invokeMethod(pausedAgents, "take", threadId);
            assertThat(remainingPausedAgent).isNull();
            assertThat(pendingTerminal.join().state())
                    .isEqualTo(com.wzx.babiq.server.application.action.PendingApplicationAction.State.EXPIRED);
            org.assertj.core.api.Assertions.assertThatThrownBy(created::start)
                    .isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(waiting::resume)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void realApplicationActionToolCallbackCompletesThroughAuthenticatedBridgeAndPersistsCorrelation()
            throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(received)) {
            request(session, received, 11, "application/identity/bind", identity());
            request(session, received, 12, "application/catalog/register", catalog());
            request(session, received, 13, "application/context/publish", pageContext());
            JsonNode thread = request(session, received, 14, "thread/create",
                    json.createObjectNode().put("cwd", RUNTIME.toString()));
            String threadId = thread.path("threadId").asText();
            BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                    INSTANCE_ID, DESKTOP_SESSION_ID, "auth-1", 1,
                    "user-1", "tenant-1", "platform-1");
            var turn = conversations.startTurn(threadId, scope);
            turn.start();
            String turnId = turn.id();
            conversations.persistTurnStarted(
                    turn, "read current case", "dashscope-default", "qwen-plus",
                    RUNTIME.toString(), "READ_ONLY", "NEVER");
            String toolCallId = "tool-bridge-" + UUID.randomUUID();
            toolCalls.recordStarted(toolCallId, threadId, turnId, "application_action", "{}",
                    "babiq_agent", null, null, scope, java.time.Instant.now());

            CapturedItemEmitter capturedItems = capturedItemEmitter(threadId, turnId);
            ItemEmitter emitter = capturedItems.emitter();
            ToolCallback callback = java.util.Arrays.stream(tools.localCallbacks())
                    .filter(candidate -> candidate.getToolDefinition().name().equals("application_action"))
                    .findFirst().orElseThrow();
            var resultFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (ApplicationToolInvocationContext.Scope ignored = ApplicationToolInvocationContext.install(
                        new ApplicationToolInvocationContext.Invocation(toolCallId, threadId, turnId, scope))) {
                    return callback.call("""
                            {"actionId":"case.read","actionVersion":1,"input":{},
                             "pageId":"case-page","contextRevision":1}
                            """, new ToolContext(Map.of(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter)));
                }
            });

            JsonNode outbound = awaitOutbound(received, "application/action/request");
            long outboundId = outbound.path("id").asLong();
            JsonNode action = outbound.path("params");
            String executionId = action.path("executionId").asText();
            assertThat(action.path("payload").path("input").path("executionId").asText())
                    .isEqualTo(executionId);
            send(session, response(outboundId,
                    json.createObjectNode().put("accepted", true).put("executionId", executionId)));
            request(session, received, 15, "application/action/accepted",
                    actionProgress(action, "received", null));
            request(session, received, 16, "application/action/running",
                    actionProgress(action, "executing", null));
            request(session, received, 17, "application/action/completed",
                    actionProgress(action, "succeeded", json.createObjectNode().put("output", "ok")));

            JsonNode toolResult = json.readTree(resultFuture.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(toolResult.path("status").asText()).isEqualTo("completed");
            assertThat(toolResult.path("executionId").asText()).isEqualTo(executionId);
            assertThat(toolCalls.listByTurnId(turnId, scope)).singleElement()
                    .satisfies(record -> assertThat(record.toolCallId()).isEqualTo(toolCallId));
            assertThat(jdbc.queryForList(
                    "SELECT tool_call_id, execution_id FROM bq_tool_calls WHERE turn_id = ? "
                            + "AND desktop_instance_id = ? AND desktop_session_id = ? "
                            + "AND auth_session_id = ? AND identity_epoch = ? AND user_id = ? "
                            + "AND tenant_id = ? AND platform_id = ?",
                    turnId, scope.desktopInstanceId(), scope.desktopSessionId(), scope.authSessionId(),
                    scope.identityEpoch(), scope.userId(), scope.tenantId(), scope.platformId()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.get("tool_call_id")).isEqualTo(toolCallId);
                        assertThat(row.get("execution_id")).isEqualTo(executionId);
                    });
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    toolCalls.bindExecutionId(toolCallId, scope, "different-" + executionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("binding conflict");
            assertThat(conversationRepository.listItems(threadId, 20, null, scope)).singleElement()
                    .satisfies(item -> {
                        assertThat(item.type()).isEqualTo("applicationAction");
                        assertThat(item.status()).isEqualTo("completed");
                        assertThat(read(item.payloadJson()).path("executionId").asText()).isEqualTo(executionId);
                    });
            assertApplicationActionItemSequence(capturedItems.messages(), executionId,
                    "requested", "accepted", "running", "completed");
        }
    }

    @Test
    void desktopValidationFailureCompletesToolImmediatelyAndPersistsFailedSequence() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(received)) {
            BridgeInvocation invocation = beginInvocation(session, received, 80, "case.read", "read_only");
            JsonNode outbound = awaitOutbound(received, "application/action/request");
            JsonNode action = outbound.path("params");
            String executionId = action.path("executionId").asText();
            send(session, response(outbound.path("id").asLong(),
                    json.createObjectNode().put("accepted", true).put("executionId", executionId)));
            request(session, received, 84, "application/action/accepted",
                    actionProgress(action, "received", null));
            request(session, received, 85, "application/action/running",
                    actionProgress(action, "executing", null));
            request(session, received, 86, "application/action/failed",
                    actionProgress(action, "failed",
                            json.createObjectNode().put("errorCode", "validation_failed")));

            JsonNode toolResult = json.readTree(
                    invocation.result().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(toolResult.path("status").asText()).isEqualTo("failed");
            assertThat(toolResult.path("errorCode").asText()).isEqualTo("validation_failed");
            assertToolCallExecutionBinding(invocation, executionId);
            assertStoredApplicationAction(invocation, executionId, "failed");
            assertApplicationActionItemSequence(invocation.itemMessages(), executionId,
                    "requested", "accepted", "running", "failed");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(applicationActionStates(executionId))
                            .containsExactly("REQUESTED", "ACCEPTED", "EXECUTING", "FAILED"));
        }
    }

    @Test
    void highRiskApprovalDenialReturnsTerminalWithoutRunning() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(received)) {
            BridgeInvocation invocation = beginInvocation(session, received, 30, "case.delete", "high_risk");
            JsonNode outbound = awaitOutbound(received, "application/action/request");
            JsonNode action = outbound.path("params");
            String executionId = action.path("executionId").asText();
            send(session, response(outbound.path("id").asLong(),
                    json.createObjectNode().put("accepted", true).put("executionId", executionId)));
            request(session, received, 34, "application/action/accepted", actionProgress(action, "received", null));
            request(session, received, 35, "application/action/previewed", actionProgress(action, "previewed",
                    json.createObjectNode().put("previewSummary", "delete current case")));
            request(session, received, 36, "application/action/approval-required",
                    actionProgress(action, "waiting_approval", null));
            request(session, received, 37, "application/action/canceled", actionProgress(action, "canceled",
                    json.createObjectNode().put("errorCode", "approval_denied")));

            JsonNode toolResult = json.readTree(invocation.result().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(toolResult.path("status").asText()).isEqualTo("canceled");
            assertThat(toolResult.path("errorCode").asText()).isEqualTo("approval_denied");
            assertToolCallExecutionBinding(invocation, executionId);
            assertApplicationActionItemSequence(invocation.itemMessages(), executionId,
                    "requested", "accepted", "previewed", "approval_required", "canceled");
            assertStoredApplicationAction(invocation, executionId, "canceled");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(applicationActionStates(executionId))
                            .containsExactly("REQUESTED", "ACCEPTED", "PREVIEWED", "APPROVAL_REQUIRED", "CANCELED")
                            .doesNotContain("EXECUTING"));
        }
    }

    @Test
    void runningCancelRaceKeepsOneTerminalAndAuditsLateDesktopResult() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(received)) {
            BridgeInvocation invocation = beginInvocation(session, received, 40, "case.read", "read_only");
            JsonNode outbound = awaitOutbound(received, "application/action/request");
            JsonNode action = outbound.path("params");
            String executionId = action.path("executionId").asText();
            send(session, response(outbound.path("id").asLong(),
                    json.createObjectNode().put("accepted", true).put("executionId", executionId)));
            request(session, received, 44, "application/action/accepted", actionProgress(action, "received", null));
            request(session, received, 45, "application/action/running", actionProgress(action, "executing", null));

            request(session, received, 46, "turn/cancel",
                    json.createObjectNode().put("turnId", invocation.turnId()));
            JsonNode cancel = awaitOutbound(received, "application/action/cancel");
            send(session, response(cancel.path("id").asLong(), json.createObjectNode().put("accepted", false)));
            JsonNode lateResponse = requestResponse(session, received, 47, "application/action/completed",
                    actionProgress(action, "succeeded", json.createObjectNode().put("output", "late")));
            assertThat(lateResponse.path("error").path("code").asInt()).isEqualTo(-32602);

            JsonNode toolResult = json.readTree(invocation.result().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(toolResult.path("status").asText()).isEqualTo("outcome_unknown");
            assertToolCallExecutionBinding(invocation, executionId);
            assertApplicationActionItemSequence(invocation.itemMessages(), executionId,
                    "requested", "accepted", "running", "outcome_unknown");
            assertStoredApplicationAction(invocation, executionId, "outcome_unknown");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(applicationActionEvents(executionId))
                    .anySatisfy(event -> assertThat(event.path("late_result").asInt()).isEqualTo(1)));
        }
    }

    @Test
    void duplicateExecutionIdIsRejectedByTheRealPendingRegistry() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(received)) {
            BridgeInvocation invocation = beginInvocation(session, received, 70, "case.read", "read_only");
            JsonNode outbound = awaitOutbound(received, "application/action/request");
            String executionId = outbound.path("params").path("executionId").asText();
            TrustedDesktopConnection connection = connections.findByDesktopSessionId(DESKTOP_SESSION_ID).orElseThrow();
            var identity = identities.current(connection).orElseThrow();
            var conflictingCorrelation =
                    new com.wzx.babiq.server.application.action.PendingApplicationAction.Correlation(
                            invocation.threadId(), invocation.turnId(), "other-tool-call");
            var context = new com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext(
                    connection.reservationId(), connection.webSocketSessionId(), connection.desktopInstanceId(),
                    connection.desktopSessionId(), identity.authSessionId(), identity.identityEpoch(),
                    identity.userId(), identity.tenantId(), identity.platformId());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> pendingActions().register(
                            executionId, conflictingCorrelation,
                            com.wzx.babiq.server.application.action.PendingApplicationAction.Path.READ_ONLY, context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(executionId);

            send(session, response(outbound.path("id").asLong(),
                    json.createObjectNode().put("accepted", false).put("executionId", executionId)));
            assertThat(json.readTree(invocation.result().get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .path("status").asText()).isEqualTo("rejected");
            assertToolCallExecutionBinding(invocation, executionId);
            assertApplicationActionItemSequence(invocation.itemMessages(), executionId, "requested", "rejected");
            assertStoredApplicationAction(invocation, executionId, "rejected");
        }
    }

    @Test
    void lostAcknowledgementReconcilesPersistedDesktopTerminalWithoutDispatchRetry() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        BridgeInvocation invocation;
        String executionId;
        try (WebSocketSession session = connect(received)) {
            invocation = beginInvocation(session, received, 60, "case.read", "read_only");
            JsonNode outbound = awaitOutbound(received, "application/action/request");
            JsonNode action = outbound.path("params");
            executionId = action.path("executionId").asText();
            request(session, received, 64, "application/action/accepted", actionProgress(action, "received", null));
            request(session, received, 65, "application/action/running", actionProgress(action, "executing", null));

            session.close();

            JsonNode toolResult = json.readTree(invocation.result().get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(toolResult.path("status").asText()).isEqualTo("outcome_unknown");
            assertToolCallExecutionBinding(invocation, executionId);
            assertApplicationActionItemSequence(invocation.itemMessages(), executionId,
                    "requested", "accepted", "running", "outcome_unknown");
            assertStoredApplicationAction(invocation, executionId, "outcome_unknown");
            assertThat(received.stream().map(this::read)
                    .filter(node -> "application/action/request".equals(node.path("method").asText())))
                    .hasSize(1);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(applicationActionStatus(executionId)).isEqualTo("OUTCOME_UNKNOWN"));
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(connections.findByDesktopSessionId(DESKTOP_SESSION_ID)).isEmpty());
        List<String> reconnected = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(reconnected)) {
            request(session, reconnected, 66, "application/identity/bind", identity());
            JsonNode statusQuery = awaitOutbound(reconnected, "application/action/status");
            assertThat(statusQuery.path("params").path("executionId").asText()).isEqualTo(executionId);
            send(session, response(statusQuery.path("id").asLong(),
                    json.createObjectNode().put("executionId", executionId).put("state", "succeeded")));
            JsonNode resultQuery = awaitOutbound(reconnected, "application/action/result/get");
            assertThat(resultQuery.path("params").path("executionId").asText()).isEqualTo(executionId);
            send(session, response(resultQuery.path("id").asLong(),
                    json.createObjectNode().put("executionId", executionId).put("state", "succeeded")
                            .put("output", "persisted desktop result")));

            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(applicationActionEvents(executionId)).anySatisfy(event -> {
                        assertThat(event.path("event_type").asText()).isEqualTo("late_result");
                        assertThat(event.path("to_status").asText()).isEqualTo("COMPLETED");
                        assertThat(event.path("late_result").asInt()).isEqualTo(1);
                    }));
            assertToolCallExecutionBinding(invocation, executionId);
            assertApplicationActionItemSequence(invocation.itemMessages(), executionId,
                    "requested", "accepted", "running", "outcome_unknown");
            assertStoredApplicationAction(invocation, executionId, "outcome_unknown");
            assertThat(reconnected.stream().map(this::read)
                    .filter(node -> "application/action/request".equals(node.path("method").asText())))
                    .isEmpty();
        }
    }

    private BridgeInvocation beginInvocation(
            WebSocketSession session, List<String> received, long requestBase,
            String actionId, String risk) throws Exception {
        request(session, received, requestBase, "application/identity/bind", identity());
        request(session, received, requestBase + 1, "application/catalog/register", catalog(actionId, risk));
        request(session, received, requestBase + 2, "application/context/publish", pageContext());
        JsonNode thread = request(session, received, requestBase + 3, "thread/create",
                json.createObjectNode().put("cwd", RUNTIME.toString()));
        String threadId = thread.path("threadId").asText();
        BusinessIdentityScope scope = scope();
        var turn = conversations.startTurn(threadId, scope);
        turn.start();
        conversations.persistTurnStarted(turn, "bridge action", "dashscope-default", "qwen-plus",
                RUNTIME.toString(), "READ_ONLY", "NEVER");
        String toolCallId = "tool-bridge-" + UUID.randomUUID();
        toolCalls.recordStarted(toolCallId, threadId, turn.id(), "application_action", "{}",
                "babiq_agent", null, null, scope, java.time.Instant.now());
        CapturedItemEmitter capturedItems = capturedItemEmitter(threadId, turn.id());
        ItemEmitter emitter = capturedItems.emitter();
        ToolCallback callback = java.util.Arrays.stream(tools.localCallbacks())
                .filter(candidate -> candidate.getToolDefinition().name().equals("application_action"))
                .findFirst().orElseThrow();
        var arguments = json.createObjectNode()
                .put("actionId", actionId)
                .put("actionVersion", 1)
                .put("pageId", "case-page")
                .put("contextRevision", 1);
        arguments.set("input", json.createObjectNode());
        var result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try (ApplicationToolInvocationContext.Scope ignored = ApplicationToolInvocationContext.install(
                    new ApplicationToolInvocationContext.Invocation(toolCallId, threadId, turn.id(), scope))) {
                return (String) callback.call(arguments.toString(),
                        new ToolContext(Map.of(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter)));
            }
        });
        return new BridgeInvocation(threadId, turn.id(), toolCallId, scope, result, capturedItems.messages());
    }

    @Autowired
    private com.wzx.babiq.server.application.action.PendingApplicationActions pendingApplicationActions;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private com.wzx.babiq.server.application.action.PendingApplicationActions pendingActions() {
        return pendingApplicationActions;
    }

    private List<JsonNode> applicationActionEvents(String executionId) {
        return jdbc.query("SELECT event_type, to_status, late_result "
                        + "FROM bq_application_action_events WHERE execution_id = ? ORDER BY event_sequence",
                (resultSet, rowNum) -> json.createObjectNode()
                        .put("event_type", resultSet.getString(1))
                        .put("to_status", resultSet.getString(2))
                        .put("late_result", resultSet.getInt(3)), executionId);
    }

    private List<String> applicationActionStates(String executionId) {
        return jdbc.query("SELECT to_status FROM bq_application_action_events "
                        + "WHERE execution_id = ? ORDER BY event_sequence",
                (resultSet, rowNum) -> resultSet.getString(1), executionId);
    }

    private String applicationActionStatus(String executionId) {
        return jdbc.queryForObject("SELECT status FROM bq_application_actions WHERE execution_id = ?",
                String.class, executionId);
    }

    private void assertToolCallExecutionBinding(BridgeInvocation invocation, String executionId) {
        assertThat(jdbc.queryForList(
                "SELECT tool_call_id, execution_id FROM bq_tool_calls WHERE turn_id = ? "
                        + "AND desktop_instance_id = ? AND desktop_session_id = ? "
                        + "AND auth_session_id = ? AND identity_epoch = ? AND user_id = ? "
                        + "AND tenant_id = ? AND platform_id = ?",
                invocation.turnId(), invocation.scope().desktopInstanceId(),
                invocation.scope().desktopSessionId(), invocation.scope().authSessionId(),
                invocation.scope().identityEpoch(), invocation.scope().userId(),
                invocation.scope().tenantId(), invocation.scope().platformId()))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("tool_call_id")).isEqualTo(invocation.toolCallId());
                    assertThat(row.get("execution_id")).isEqualTo(executionId);
                });
    }

    private void assertApplicationActionItemSequence(
            List<String> received, String executionId, String... expectedStatuses) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(received.stream()
                .map(this::read)
                .filter(message -> "item/added".equals(message.path("method").asText())
                        || "item/updated".equals(message.path("method").asText()))
                .map(message -> message.path("params").path("item"))
                .filter(item -> "applicationAction".equals(item.path("type").asText()))
                .filter(item -> executionId.equals(item.path("executionId").asText()))
                .map(item -> item.path("status").asText())
                .toList()).containsExactly(expectedStatuses));
    }

    private void assertStoredApplicationAction(
            BridgeInvocation invocation, String executionId, String expectedStatus) {
        assertThat(conversationRepository.listItems(
                invocation.threadId(), 20, null, invocation.scope()).stream()
                .filter(item -> "applicationAction".equals(item.type()))
                .filter(item -> executionId.equals(read(item.payloadJson()).path("executionId").asText()))
                .toList()).singleElement().satisfies(item -> {
                    assertThat(item.status()).isEqualTo(expectedStatus);
                    JsonNode payload = read(item.payloadJson());
                    assertThat(payload.path("executionId").asText()).isEqualTo(executionId);
                    assertThat(payload.path("type").asText()).isEqualTo("applicationAction");
                });
    }

    private CapturedItemEmitter capturedItemEmitter(String threadId, String turnId) throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0, TextMessage.class).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return new CapturedItemEmitter(new ItemEmitter(session, json, threadId, turnId, recorder), messages);
    }

    private BusinessIdentityScope scope() {
        return BusinessIdentityScope.scoped(
                INSTANCE_ID, DESKTOP_SESSION_ID, "auth-1", 1,
                "user-1", "tenant-1", "platform-1");
    }

    private record BridgeInvocation(
            String threadId, String turnId, String toolCallId, BusinessIdentityScope scope,
            java.util.concurrent.CompletableFuture<String> result,
            List<String> itemMessages) {
    }

    private record CapturedItemEmitter(ItemEmitter emitter, List<String> messages) {
    }

    private WebSocketSession connect(List<String> received) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        headers.set("X-Desktop-Instance-Id", INSTANCE_ID);
        headers.set("X-Desktop-Session-Id", DESKTOP_SESSION_ID);
        headers.setOrigin("http://127.0.0.1");
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

    private JsonNode request(
            WebSocketSession session,
            List<String> received,
            long id,
            String method,
            JsonNode params) throws Exception {
        JsonNode response = requestResponse(session, received, id, method, params);
        assertThat(response.path("error").isMissingNode()).as(response.toString()).isTrue();
        return response.path("result");
    }

    private JsonNode requestResponse(
            WebSocketSession session,
            List<String> received,
            long id,
            String method,
            JsonNode params) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .set("params", params))));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(received.stream().map(this::read)
                        .anyMatch(node -> !node.has("method") && node.path("id").asLong() == id)).isTrue());
        JsonNode response = received.stream().map(this::read)
                .filter(node -> !node.has("method") && node.path("id").asLong() == id)
                .findFirst().orElseThrow();
        return response;
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

    private JsonNode actionProgress(JsonNode request, String state, JsonNode extraPayload) {
        var progress = request.deepCopy();
        var payload = json.createObjectNode().put("state", state);
        if (extraPayload != null) {
            payload.setAll((com.fasterxml.jackson.databind.node.ObjectNode) extraPayload);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) progress).set("payload", payload);
        return progress;
    }

    private JsonNode identity() {
        var message = envelope(1)
                .put("authSessionId", "auth-1")
                .put("identityEpoch", 1)
                .put("userId", "user-1")
                .put("tenantId", "tenant-1")
                .put("platformId", "platform-1")
                .put("authenticated", true);
        message.putArray("roles").add("lawyer");
        message.putArray("permissions").add("case:read");
        return message;
    }

    private JsonNode identityUpdate() {
        var message = envelope(4)
                .put("authSessionId", "auth-2")
                .put("identityEpoch", 2)
                .put("userId", "user-2")
                .put("tenantId", "tenant-2")
                .put("platformId", "platform-1")
                .put("authenticated", true);
        message.putArray("roles").add("lawyer");
        message.putArray("permissions").add("case:read");
        return message;
    }

    private JsonNode catalog() {
        return catalog("case.read", "read_only");
    }

    private JsonNode catalog(String actionId, String risk) {
        var payload = json.createObjectNode();
        var action = payload.putObject("actions").putObject(actionId);
        action.put("id", actionId).put("version", 1).put("risk", risk).put("enabled", true);
        action.putArray("requiredPermissions").add("case:read");
        var inputSchema = action.putObject("inputSchema");
        inputSchema.put("type", "object");
        inputSchema.putObject("properties").putObject("executionId").put("type", "string");
        inputSchema.putArray("required").add("executionId");
        return catalogEnvelope(2, 1, payload);
    }

    private JsonNode pageContext() {
        var payload = json.createObjectNode().put("pageId", "case-page").put("contextRevision", 1);
        return catalogEnvelope(3, 1, payload).put("contextSequence", 1);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode catalogEnvelope(
            long sequence, long catalogEpoch, JsonNode payload) {
        return envelope(sequence)
                .put("authSessionId", "auth-1")
                .put("identityEpoch", 1)
                .put("userId", "user-1")
                .put("tenantId", "tenant-1")
                .put("platformId", "platform-1")
                .put("catalogEpoch", catalogEpoch)
                .put("contextSequence", sequence)
                .put("payloadSize", payload.toString().getBytes(StandardCharsets.UTF_8).length)
                .set("payload", payload);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode envelope(long sequence) {
        return json.createObjectNode()
                .put("protocolVersion", "1.0")
                .put("desktopInstanceId", INSTANCE_ID)
                .put("desktopSessionId", DESKTOP_SESSION_ID)
                .put("sequence", sequence)
                .put("generatedAt", "2026-07-18T00:00:00Z");
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
