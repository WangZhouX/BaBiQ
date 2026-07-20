package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.attachment.AttachmentErrorCode;
import com.wzx.babiq.server.attachment.AttachmentException;
import com.wzx.babiq.server.attachment.AttachmentHistoryResolver;
import com.wzx.babiq.server.attachment.AttachmentMetadata;
import com.wzx.babiq.server.attachment.AttachmentPreparationService;
import com.wzx.babiq.server.attachment.AttachmentReservationRegistry;
import com.wzx.babiq.server.attachment.AttachmentSource;
import com.wzx.babiq.server.attachment.PreparedAttachment;
import com.wzx.babiq.server.attachment.PreparedTurnInput;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
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
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
import static org.mockito.Mockito.times;
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
        verify(executor).submit(
                any(),
                org.mockito.ArgumentMatchers.argThat((PreparedTurnInput input) ->
                        "ping".equals(input.text()) && input.allAttachments().isEmpty()),
                eq("dashscope-default"), eq("F:/wwwxxxx/BaBiQ"),
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

        verify(executor).submit(
                any(),
                org.mockito.ArgumentMatchers.argThat((PreparedTurnInput input) ->
                        "create file".equals(input.text()) && input.allAttachments().isEmpty()),
                eq(null), eq("H:/aaa"), any(),
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
        verify(executor, never()).submit(
                any(), org.mockito.ArgumentMatchers.any(String.class),
                any(), any(), any(), any(), any());
        assertThat(payloads).anyMatch(payload -> payload.contains("\"method\":\"item/added\"")
                && payload.contains("\"type\":\"workUnit\""));
        assertThat(payloads).anyMatch(payload -> payload.contains("\"method\":\"turn/completed\""));
    }

    @Test
    void create_work_unit_with_a_new_attachment_is_rejected_before_turn_or_event_mutation() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        PreparedAttachment selected = preparedAttachment(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q");
        PreparedTurnInput prepared = new PreparedTurnInput(
                "/编排 合同审阅", List.of(selected), List.of());
        when(preparationService.prepareNew(eq("/编排 合同审阅"), any())).thenReturn(prepared);
        when(historyResolver.resolve(thread.id(), BusinessIdentityScope.UNSCOPED, prepared))
                .thenReturn(prepared);
        List<String> payloads = new ArrayList<>();
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, workUnitService, null,
                preparationService, historyResolver);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of(
                                "type", "text",
                                "text", "/编排 合同审阅",
                                "attachments", List.of(Map.of(
                                        "id", selected.metadata().id(),
                                        "displayId", selected.metadata().displayId(),
                                        "name", selected.metadata().name(),
                                        "localPath", selected.metadata().localPath()))),
                        "executionIntent", workUnitIntent())),
                recordingSession(payloads)))
                .isInstanceOfSatisfying(JsonRpcException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));

        assertNoTurnOrWorkUnitMutation(
                conversationService, executor, workUnitService, payloads, thread.id());
    }

    @Test
    void create_work_unit_with_a_resolved_text_reference_is_rejected_before_turn_or_event_mutation() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        PreparedAttachment referenced = preparedAttachment(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q");
        PreparedTurnInput prepared = new PreparedTurnInput(
                "/编排 审阅 A-7K3M2Q", List.of(), List.of());
        PreparedTurnInput resolved = new PreparedTurnInput(
                prepared.text(), List.of(), List.of(referenced));
        when(preparationService.prepareNew(eq(prepared.text()), any())).thenReturn(prepared);
        when(historyResolver.resolve(thread.id(), BusinessIdentityScope.UNSCOPED, prepared))
                .thenReturn(resolved);
        List<String> payloads = new ArrayList<>();
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, workUnitService, null,
                preparationService, historyResolver);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", prepared.text()),
                        "executionIntent", workUnitIntent())),
                recordingSession(payloads)))
                .isInstanceOfSatisfying(JsonRpcException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));

        assertNoTurnOrWorkUnitMutation(
                conversationService, executor, workUnitService, payloads, thread.id());
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
        verify(executor).submit(
                any(),
                org.mockito.ArgumentMatchers.argThat((PreparedTurnInput input) ->
                        "start html-test".equals(input.text()) && input.allAttachments().isEmpty()),
                eq(null), eq("H:/aaa"), any(), any(), eq("goal_1"));
    }

    @Test
    void invalid_attachment_prevents_start_work_unit_goal_selection_and_all_turn_mutation() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        when(preparationService.prepareNew(eq("start attached work unit"), any()))
                .thenThrow(new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_NOT_FOUND,
                        "附件已不存在"));
        when(workUnitService.selectPendingGoalForTurn(thread.id(), "wu_1"))
                .thenReturn(workUnitGoal(thread.id()));
        List<String> payloads = new ArrayList<>();
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, workUnitService, null,
                preparationService, historyResolver);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of(
                                "type", "text",
                                "text", "start attached work unit",
                                "attachments", List.of(Map.of(
                                        "id", "550e8400-e29b-41d4-a716-446655440000",
                                        "displayId", "A-7K3M2Q",
                                        "name", "missing.pdf",
                                        "localPath", "C:\\missing.pdf"))),
                        "executionIntent", Map.of(
                                "type", "start_work_unit",
                                "workUnitId", "wu_1"))),
                recordingSession(payloads)))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code()).isEqualTo(AttachmentErrorCode.ATTACHMENT_NOT_FOUND));

        verify(workUnitService, never()).selectPendingGoalForTurn(any(), any());
        assertNoTurnOrWorkUnitMutation(
                conversationService, executor, workUnitService, payloads, thread.id());
    }

    @Test
    void handle_should_allow_attachment_only_input_and_submit_one_immutable_prepared_input() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        PreparedAttachment attachment = preparedAttachment(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q");
        PreparedTurnInput prepared = new PreparedTurnInput("", List.of(attachment), List.of());
        when(preparationService.prepareNew(eq(""), any())).thenReturn(prepared);
        when(historyResolver.resolve(
                eq(thread.id()), eq(BusinessIdentityScope.UNSCOPED), eq(prepared)))
                .thenReturn(prepared);
        List<String> payloads = new ArrayList<>();
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, null, null,
                preparationService, historyResolver);

        handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of(
                                "type", "text",
                                "text", "",
                                "attachments", List.of(Map.of(
                                        "id", attachment.metadata().id(),
                                        "displayId", attachment.metadata().displayId(),
                                        "name", attachment.metadata().name(),
                                        "localPath", attachment.metadata().localPath()))))),
                recordingSession(payloads));

        assertThat(payloads).singleElement().asString().contains("\"method\":\"turn/started\"");
        ArgumentCaptor<PreparedTurnInput> submitted = ArgumentCaptor.forClass(PreparedTurnInput.class);
        verify(executor).submit(
                any(), submitted.capture(), eq(null), eq("C:/business"), any(), any(), eq((String) null));
        assertThat(submitted.getValue()).isSameAs(prepared);
        assertThat(submitted.getValue().newAttachments()).containsExactly(attachment);
    }

    @Test
    void pending_attachment_identity_is_reserved_before_a_second_history_scan_or_turn() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = preparedAttachment(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q");
        PreparedTurnInput prepared = new PreparedTurnInput(
                "review", List.of(attachment), List.of());
        when(preparationService.prepareNew(eq("review"), any())).thenReturn(prepared);
        when(historyResolver.resolve(thread.id(), BusinessIdentityScope.UNSCOPED, prepared))
                .thenReturn(prepared);
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, null, null,
                preparationService, historyResolver, registry);
        var params = objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of(
                        "type", "text",
                        "text", "review",
                        "attachments", List.of(Map.of(
                                "id", attachment.metadata().id(),
                                "displayId", attachment.metadata().displayId(),
                                "name", attachment.metadata().name(),
                                "localPath", attachment.metadata().localPath())))));

        Map<?, ?> first = (Map<?, ?>) handler.handle(params, recordingSession(new ArrayList<>()));
        assertThatThrownBy(() -> handler.handle(params, recordingSession(new ArrayList<>())))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS));

        verify(historyResolver, times(1))
                .resolve(thread.id(), BusinessIdentityScope.UNSCOPED, prepared);
        verify(conversationService, times(1))
                .startTurn(thread.id(), BusinessIdentityScope.UNSCOPED);
        verify(executor, times(1)).submit(
                any(), any(PreparedTurnInput.class), any(), any(), any(), any(), any());
        registry.releaseTurn(String.valueOf(first.get("turnId")));
    }

    @Test
    void synchronous_executor_rejection_releases_the_pending_attachment_identity() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        doThrow(new RejectedExecutionException("unavailable"))
                .when(executor).submit(
                        any(), any(PreparedTurnInput.class), any(), any(), any(), any(), any());
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        AttachmentReservationRegistry registry = new AttachmentReservationRegistry();
        PreparedAttachment attachment = preparedAttachment(
                "550e8400-e29b-41d4-a716-446655440000", "A-7K3M2Q");
        PreparedTurnInput prepared = new PreparedTurnInput(
                "review", List.of(attachment), List.of());
        when(preparationService.prepareNew(eq("review"), any())).thenReturn(prepared);
        when(historyResolver.resolve(thread.id(), BusinessIdentityScope.UNSCOPED, prepared))
                .thenReturn(prepared);
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, null, null,
                preparationService, historyResolver, registry);
        var params = objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of(
                        "type", "text",
                        "text", "review",
                        "attachments", List.of(Map.of(
                                "id", attachment.metadata().id(),
                                "displayId", attachment.metadata().displayId(),
                                "name", attachment.metadata().name(),
                                "localPath", attachment.metadata().localPath())))));

        assertThatThrownBy(() -> handler.handle(params, recordingSession(new ArrayList<>())))
                .isInstanceOf(JsonRpcException.class);

        try (AttachmentReservationRegistry.Reservation next = registry.reserve(
                thread.id(), BusinessIdentityScope.UNSCOPED, List.of(attachment))) {
            assertThat(next.active()).isTrue();
        }
    }

    @Test
    void handle_should_reject_empty_text_and_empty_attachments_before_creating_a_turn() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        List<String> payloads = new ArrayList<>();
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, executor);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "", "attachments", List.of()))),
                recordingSession(payloads)))
                .isInstanceOf(JsonRpcException.class);

        assertThat(payloads).isEmpty();
        verify(conversationService, never()).startTurn(any());
        verify(conversationService, never()).startTurn(any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    void invalid_attachment_fails_before_turn_started_persistence_or_executor_submission() {
        ConversationService conversationService = spy(new ConversationService());
        Thread thread = conversationService.createThread("C:/business");
        TurnExecutor executor = mock(TurnExecutor.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        when(preparationService.prepareNew(eq("review"), any()))
                .thenThrow(new AttachmentException(
                        AttachmentErrorCode.ATTACHMENT_NOT_FOUND,
                        "附件已不存在"));
        List<String> payloads = new ArrayList<>();
        TurnStartHandler handler = new TurnStartHandler(
                conversationService, objectMapper, executor,
                null, null, null, null, null, null,
                preparationService, historyResolver);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of(
                                "type", "text",
                                "text", "review",
                                "attachments", List.of(Map.of(
                                        "id", "550e8400-e29b-41d4-a716-446655440000",
                                        "displayId", "A-7K3M2Q",
                                        "name", "missing.pdf",
                                        "localPath", "C:\\missing.pdf"))))),
                recordingSession(payloads)))
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        assertThat(failure.code()).isEqualTo(AttachmentErrorCode.ATTACHMENT_NOT_FOUND));

        assertThat(payloads).isEmpty();
        verify(conversationService, never()).startTurn(any());
        verify(conversationService, never()).startTurn(any(), any());
        verify(conversationService, never()).persistTurnStarted(
                any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(executor);
        verifyNoInteractions(historyResolver);
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
                .when(executor).submit(
                        any(), org.mockito.ArgumentMatchers.any(PreparedTurnInput.class),
                        any(), any(), any(), any(), any());
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, executor);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "input", Map.of("type", "text", "text", "ping"))),
                recordingSession(new ArrayList<>())))
                .isInstanceOf(JsonRpcException.class);

        ArgumentCaptor<com.wzx.babiq.server.conversation.Turn> turnCaptor =
                ArgumentCaptor.forClass(com.wzx.babiq.server.conversation.Turn.class);
        verify(executor).submit(
                turnCaptor.capture(),
                org.mockito.ArgumentMatchers.argThat((PreparedTurnInput input) ->
                        "ping".equals(input.text()) && input.allAttachments().isEmpty()),
                eq(null), eq("C:/business"), any(), any(), eq((String) null));
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

    private static PreparedAttachment preparedAttachment(String id, String displayId) {
        AttachmentMetadata metadata = new AttachmentMetadata(
                id,
                displayId,
                "contract.pdf",
                "C:\\business\\contract.pdf",
                "application/pdf",
                42,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(
                metadata,
                Path.of(metadata.localPath()),
                new PreparedAttachment.FileIdentity(
                        metadata.sizeBytes(), FileTime.from(Instant.EPOCH), "file-key"));
    }

    private static Map<String, String> workUnitIntent() {
        return Map.of(
                "type", "create_work_unit",
                "kind", "orchestration",
                "name", "合同审阅",
                "goal", "审阅合同");
    }

    private static WorkUnitGoal workUnitGoal(String threadId) {
        return new WorkUnitGoal(
                "goal_1", "wu_1", threadId, "run", "pending",
                null, null, null, null, Instant.EPOCH, null, null);
    }

    private static void assertNoTurnOrWorkUnitMutation(
            ConversationService conversationService,
            TurnExecutor executor,
            WorkUnitService workUnitService,
            List<String> payloads,
            String threadId
    ) {
        assertThat(payloads).isEmpty();
        verify(conversationService, never()).startTurn(eq(threadId), any());
        verify(conversationService, never()).persistTurnStarted(
                any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(executor, workUnitService);
    }
}
