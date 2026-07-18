package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.settings.AppSettings;
import com.wzx.babiq.server.settings.AppSettingsService;
import com.wzx.babiq.server.workunit.WorkUnitCreateRequest;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * TurnStartHandler 测试。
 *
 * <p>P1-3a 起 handler 只负责创建 turn、发 turn/started、提交 TurnExecutor。</p>
 */
class TurnStartHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_should_return_turn_id_emit_started_and_submit_executor() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("F:/wwwxxxx/BaBiQ");
        TurnExecutor executor = mock(TurnExecutor.class);
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, executor);

        Object responsePayload = handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "providerId", "dashscope-default",
                        "input", Map.of("type", "text", "text", "ping"))),
                session);

        Map<?, ?> responseMap = (Map<?, ?>) responsePayload;
        assertThat(responseMap.get("turnId")).asString().startsWith("turn_");
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains("\"method\":\"turn/started\"");
        verify(executor).submit(any(), eq("ping"), eq("dashscope-default"), eq("F:/wwwxxxx/BaBiQ"),
                any(), any(), eq((String) null));
    }

    @Test
    void handle_should_submit_agent_with_current_settings_snapshot() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("H:/aaa");
        TurnExecutor executor = mock(TurnExecutor.class);
        AppSettingsService appSettingsService = mock(AppSettingsService.class);
        when(appSettingsService.get()).thenReturn(new AppSettings(
                "deepseek", SandboxMode.READ_ONLY.name(), ApprovalPolicy.NEVER.name(), "H:/aaa"));
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        TurnStartHandler handler = new TurnStartHandler(
                conversationService,
                objectMapper,
                executor,
                null,
                null,
                null,
                appSettingsService);

        handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "create file"))),
                session);

        verify(executor).submit(any(), eq("create file"), eq(null), eq("H:/aaa"), any(),
                eq(AgentRunPolicy.of(SandboxMode.READ_ONLY, ApprovalPolicy.NEVER)), eq((String) null));
    }

    @Test
    void handle_should_create_work_unit_and_complete_turn_without_agent_loop() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("H:/aaa");
        TurnExecutor executor = mock(TurnExecutor.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        when(workUnitService.createOrAppend(any(), eq(thread), any(), eq("H:/aaa"), any()))
                .thenReturn(new WorkUnitItem(
                        "it_workunit_1",
                        "workUnit",
                        "wu_1",
                        "orchestration",
                        "登录页重构",
                        "waiting_config",
                        "goal_1",
                        "拆分登录页改造流程",
                        1,
                        null));
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        TurnStartHandler handler = new TurnStartHandler(
                conversationService,
                objectMapper,
                executor,
                null,
                null,
                null,
                null,
                workUnitService);

        handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "/编排 登录页重构：拆分登录页改造流程"),
                        "executionIntent", Map.of(
                                "type", "create_work_unit",
                                "kind", "orchestration",
                                "name", "登录页重构",
                                "goal", "拆分登录页改造流程"))),
                session);

        verify(workUnitService).createOrAppend(
                org.mockito.ArgumentMatchers.argThat((WorkUnitCreateRequest request) ->
                        "orchestration".equals(request.kind())
                                && "登录页重构".equals(request.name())
                                && "拆分登录页改造流程".equals(request.goal())),
                eq(thread),
                any(),
                eq("H:/aaa"),
                any());
        verify(executor, never()).submit(any(), any(), any(), any(), any(), any(), any());
        assertThat(payloads).anyMatch(payload -> payload.contains("\"method\":\"item/added\"")
                && payload.contains("\"type\":\"workUnit\""));
        assertThat(payloads).anyMatch(payload -> payload.contains("\"method\":\"turn/completed\""));
    }

    @Test
    void handle_should_bind_start_work_unit_intent_before_submitting_agent_loop() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("H:/aaa");
        TurnExecutor executor = mock(TurnExecutor.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        when(workUnitService.selectPendingGoalForTurn(thread.id(), "wu_1"))
                .thenReturn(new WorkUnitGoal(
                        "goal_1",
                        "wu_1",
                        thread.id(),
                        "run html flow",
                        "pending",
                        null,
                        null,
                        null,
                        null,
                        java.time.Instant.parse("2026-06-03T00:00:00Z"),
                        null,
                        null));
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        TurnStartHandler handler = new TurnStartHandler(
                conversationService,
                objectMapper,
                executor,
                null,
                null,
                null,
                null,
                workUnitService);

        handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "start html-test"),
                        "executionIntent", Map.of(
                                "type", "start_work_unit",
                                "workUnitId", "wu_1"))),
                session);

        verify(workUnitService).selectPendingGoalForTurn(thread.id(), "wu_1");
        verify(executor).submit(any(), eq("start html-test"), eq(null), eq("H:/aaa"), any(), any(), eq("goal_1"));
    }

    @Test
    void businessTurnStartUsesScopedLookupBeforeCreatingAnyTurn() {
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "session", "auth", 1, "user", "tenant", "platform");
        ConversationService conversationService = mock(ConversationService.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        TurnExecutor executor = mock(TurnExecutor.class);
        WebSocketSession session = recordingSession(new ArrayList<>());
        when(scopes.resolve(session)).thenReturn(scope);
        when(scopes.withActiveConnectionScope(eq(scope), any())).thenAnswer(invocation -> {
            Function<BusinessIdentityScopeService.ActiveBusinessIdentity, Object> operation =
                    invocation.getArgument(1);
            return Optional.ofNullable(operation.apply(new BusinessIdentityScopeService.ActiveBusinessIdentity(
                    mock(com.wzx.babiq.server.application.auth.TrustedDesktopConnection.class),
                    mock(com.wzx.babiq.server.application.auth.TrustedBusinessIdentity.class))));
        });
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor, null, null, null, null, null, scopes);
        var params = objectMapper.valueToTree(Map.of(
                "threadId", "thread-a", "input", Map.of("type", "text", "text", "ping")));

        assertThatThrownBy(() -> handler.handle(params, session)).isInstanceOf(JsonRpcException.class);

        verify(conversationService).findThread("thread-a", scope);
        verify(conversationService, never()).findThread("thread-a");
        verify(conversationService, never()).startTurn(any());
        verify(conversationService, never()).startTurn(any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void businessTurnStartRevalidatesFrozenScopeInsideConnectionCriticalSection() {
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "session", "auth", 1, "user", "tenant", "platform");
        ConversationService conversationService = mock(ConversationService.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        TurnExecutor executor = mock(TurnExecutor.class);
        WebSocketSession session = recordingSession(new ArrayList<>());
        when(scopes.resolve(session)).thenReturn(scope);
        when(scopes.withActiveConnectionScope(eq(scope), any())).thenReturn(Optional.empty());
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor, null, null, null, null, null, scopes);
        var params = objectMapper.valueToTree(Map.of(
                "threadId", "thread-a", "input", Map.of("type", "text", "text", "ping")));

        assertThatThrownBy(() -> handler.handle(params, session)).isInstanceOf(JsonRpcException.class);

        verify(scopes).withActiveConnectionScope(eq(scope), any());
        verifyNoInteractions(executor);
    }

    @Test
    void synchronousExecutorRejectionFailsTheStartedTurnInsteadOfLeavingItRunning() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        doThrow(new RejectedExecutionException("secret executor payload"))
                .when(executor).submit(any(), any(), any(), any(), any(), any(), any());
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, executor);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of("type", "text", "text", "ping"))),
                recordingSession(new ArrayList<>())))
                .isInstanceOf(JsonRpcException.class);

        ArgumentCaptor<com.wzx.babiq.server.conversation.Turn> turnCaptor =
                ArgumentCaptor.forClass(com.wzx.babiq.server.conversation.Turn.class);
        verify(executor).submit(turnCaptor.capture(), eq("ping"), eq(null), eq("C:/business"),
                any(), any(), eq((String) null));
        assertThat(turnCaptor.getValue().status()).isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.FAILED);
        assertThat(turnCaptor.getValue().failureReason()).isEqualTo("turn_start_submission_failed");
        assertThat(conversationService.hasActiveTurn(thread.id())).isFalse();
    }

    @Test
    void startedNotificationFailureFailsTheTurnAndDoesNotSubmitAgentLoop() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        AtomicReference<com.wzx.babiq.server.conversation.Turn> created = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            com.wzx.babiq.server.conversation.Turn turn =
                    (com.wzx.babiq.server.conversation.Turn) invocation.callRealMethod();
            created.set(turn);
            return turn;
        }).when(conversationService).startTurn(thread.id(), BusinessIdentityScope.UNSCOPED);
        TurnExecutor executor = mock(TurnExecutor.class);
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, executor);
        WebSocketSession failingSession = (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName())) {
                        throw new java.io.IOException("secret tenant SQL path");
                    }
                    if ("getId".equals(method.getName())) return "test-session";
                    if ("isOpen".equals(method.getName())) return true;
                    return defaultValue(method.getReturnType());
                });

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of("type", "text", "text", "ping"))), failingSession))
                .isInstanceOf(JsonRpcException.class);

        assertThat(created.get()).isNotNull();
        assertThat(created.get().status()).isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.FAILED);
        assertThat(created.get().failureReason()).isEqualTo("turn_start_submission_failed");
        verifyNoInteractions(executor);
    }

    @Test
    void malformedWorkUnitIntentIsRejectedBeforeAnyTurnOrWorkUnitMutation() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor, null, null, null, null, workUnitService);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of("type", "text", "text", "/编排"),
                "executionIntent", Map.of(
                        "type", "create_work_unit",
                        "kind", "unsupported",
                        "name", "secret",
                        "goal", "goal"))), recordingSession(new ArrayList<>())))
                .isInstanceOf(JsonRpcException.class);

        verify(conversationService, never()).startTurn(eq(thread.id()), any());
        verifyNoInteractions(workUnitService, executor);
    }

    @Test
    void workUnitDurableCompletionFailureDoesNotLeaveMemoryCompleted() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        AtomicReference<com.wzx.babiq.server.conversation.Turn> created = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            com.wzx.babiq.server.conversation.Turn turn =
                    (com.wzx.babiq.server.conversation.Turn) invocation.callRealMethod();
            created.set(turn);
            return turn;
        }).when(conversationService).startTurn(thread.id(), BusinessIdentityScope.UNSCOPED);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        when(workUnitService.createOrAppend(any(), eq(thread), any(), eq("C:/business"), any()))
                .thenReturn(new WorkUnitItem(
                        "it_workunit_1", "workUnit", "wu_1", "orchestration", "登录页重构",
                        "waiting_config", "goal_1", "拆分登录页改造流程", 1, null));
        org.mockito.Mockito.doReturn(false).when(conversationService).completeTurnIfRunning(any());
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, mock(TurnExecutor.class), null, null,
                null, null, workUnitService);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of("type", "text", "text", "/编排 登录页重构：拆分登录页改造流程"),
                "executionIntent", Map.of(
                        "type", "create_work_unit", "kind", "orchestration",
                        "name", "登录页重构", "goal", "拆分登录页改造流程"))),
                recordingSession(new ArrayList<>())))
                .isInstanceOf(JsonRpcException.class);

        assertThat(created.get()).isNotNull();
        assertThat(created.get().status()).isNotEqualTo(com.wzx.babiq.server.conversation.TurnStatus.COMPLETED);
        assertThat(created.get().status()).isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.FAILED);
        assertThat(created.get().failureReason()).isEqualTo("turn_start_submission_failed");
    }

    @Test
    void workUnitCompletionNotificationFailureDoesNotRollbackDurableCompletion() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("C:/business");
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        when(workUnitService.createOrAppend(any(), eq(thread), any(), eq("C:/business"), any()))
                .thenReturn(new WorkUnitItem(
                        "it_workunit_1", "workUnit", "wu_1", "orchestration", "登录页重构",
                        "waiting_config", "goal_1", "拆分登录页改造流程", 1, null));
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        WebSocketSession completionFailingSession = (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(), new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName()) && sends.incrementAndGet() == 3) {
                        throw new java.io.IOException("completion transport unavailable");
                    }
                    if ("getId".equals(method.getName())) return "test-session";
                    if ("isOpen".equals(method.getName())) return true;
                    return defaultValue(method.getReturnType());
                });
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, mock(TurnExecutor.class), null, null,
                null, null, workUnitService);

        Object result = handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of("type", "text", "text", "/编排 登录页重构：拆分登录页改造流程"),
                "executionIntent", Map.of(
                        "type", "create_work_unit", "kind", "orchestration",
                        "name", "登录页重构", "goal", "拆分登录页改造流程"))),
                completionFailingSession);

        String turnId = String.valueOf(((Map<?, ?>) result).get("turnId"));
        assertThat(conversationService.findTurn(turnId)).get()
                .extracting(com.wzx.babiq.server.conversation.Turn::status)
                .isEqualTo(com.wzx.babiq.server.conversation.TurnStatus.COMPLETED);
    }

    private WebSocketSession recordingSession(List<String> payloads) {
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName())) {
                        payloads.add(((TextMessage) args[0]).getPayload());
                        return null;
                    }
                    if ("getId".equals(method.getName())) {
                        return "test-session";
                    }
                    if ("isOpen".equals(method.getName())) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        return 0;
    }
}
