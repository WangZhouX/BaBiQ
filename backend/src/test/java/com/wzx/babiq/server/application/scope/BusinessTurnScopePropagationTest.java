package com.wzx.babiq.server.application.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.attachment.AttachmentHistoryResolver;
import com.wzx.babiq.server.attachment.AttachmentMetadata;
import com.wzx.babiq.server.attachment.AttachmentPreparationService;
import com.wzx.babiq.server.attachment.AttachmentSource;
import com.wzx.babiq.server.attachment.PreparedAttachment;
import com.wzx.babiq.server.attachment.PreparedTurnInput;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntime;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeResult;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.api.method.TurnStartHandler;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeInput;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.timeout;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;

/** 验证业务身份在创建后只沿显式对象链传播，不再读取可变的当前登录态。 */
class BusinessTurnScopePropagationTest {

    private final BusinessIdentityScope tenantA = BusinessIdentityScope.scoped(
            "desktop-a", "desktop-session-a", "auth-a", 1,
            "user-a", "tenant-a", "platform-a");
    private final BusinessIdentityScope tenantB = BusinessIdentityScope.scoped(
            "desktop-a", "desktop-session-a", "auth-b", 2,
            "user-a", "tenant-b", "platform-a");

    @Test
    void conversationThreadTurnObservationAndContextInputKeepTheCreationScope() {
        ConversationService conversations = new ConversationService();
        Thread thread = conversations.createThread("E:/tenant-a", tenantA);
        Turn turn = conversations.startTurn(thread.id(), tenantA);
        TurnObservationContext observation = TurnObservationContext.start(
                thread.id(), turn.id(), "provider", "model", turn.businessIdentityScope());
        ContextWindowRuntimeInput input = new ContextWindowRuntimeInput(
                thread.id(), turn.id(), "input", "provider", "model", thread.cwd(), "tenant-a",
                null, 128_000, null, null, turn.businessIdentityScope());

        assertThat(thread.businessIdentityScope()).isEqualTo(tenantA);
        assertThat(turn.businessIdentityScope()).isEqualTo(tenantA);
        assertThat(observation.businessIdentityScope()).isEqualTo(tenantA);
        assertThat(input.businessIdentityScope()).isEqualTo(tenantA);
    }

    @Test
    void legacyDirectConstructionRemainsExplicitlyUnscoped() {
        Thread thread = new Thread("thr_legacy", ".", java.time.Instant.EPOCH);
        Turn turn = new Turn("turn_legacy", thread.id());

        assertThat(thread.businessIdentityScope()).isSameAs(BusinessIdentityScope.UNSCOPED);
        assertThat(turn.businessIdentityScope()).isSameAs(BusinessIdentityScope.UNSCOPED);
    }

    @Test
    void tenantMismatchUsesTheSameNotFoundResponseAndCreatesNoTurn() {
        ConversationService conversations = new ConversationService();
        Thread tenantAThread = conversations.createThread("E:/tenant-a", tenantA);
        TurnExecutor executor = mock(TurnExecutor.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(scopes.resolve(session)).thenReturn(tenantB);
        TurnStartHandler handler = new TurnStartHandler(
                conversations, new ObjectMapper(), executor,
                null, null, null, null, null, scopes);
        Object params = Map.of(
                "threadId", tenantAThread.id(),
                "input", Map.of("type", "text", "text", "read case"));

        JsonRpcException mismatch = catchStartFailure(handler, params, session);
        JsonRpcException missing = catchStartFailure(handler, Map.of(
                "threadId", "thr_missing",
                "input", Map.of("type", "text", "text", "read case")), session);

        assertThat(mismatch.errorCode()).isEqualTo(missing.errorCode());
        assertThat(normalizeThreadId(mismatch.getMessage(), tenantAThread.id()))
                .isEqualTo(normalizeThreadId(missing.getMessage(), "thr_missing"));
        assertThat(conversations.hasActiveTurn(tenantAThread.id())).isFalse();
        verify(executor, never()).submit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(PreparedTurnInput.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void agentLoopCopiesTheTurnScopeIntoObservationToolMetadataAndContextRuntimeInput() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        ContextWindowRuntime runtime = mock(ContextWindowRuntime.class);
        TurnObservationRegistry observations = new TurnObservationRegistry();
        AgentLoop loop = new AgentLoop(strategy, new PendingApprovals(), mock(TurnSummaryEmitter.class),
                observations, runtime);
        Turn turn = new Turn("turn-a", "thread-a", tenantA);
        turn.start();
        ItemEmitter emitter = mock(ItemEmitter.class);
        RunnableConfig config = RunnableConfig.builder().threadId(turn.threadId()).build();
        when(strategy.defaultRunPolicy()).thenReturn(null);
        when(strategy.resolveModelName("provider")).thenReturn("model");
        when(strategy.resolveContextWindow("provider")).thenReturn(128_000);
        when(strategy.currentToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(strategy.buildAgent(
                org.mockito.ArgumentMatchers.eq("provider"), org.mockito.ArgumentMatchers.eq("E:/tenant-a"),
                org.mockito.ArgumentMatchers.eq(emitter), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(com.wzx.babiq.server.agent.AgentRunPolicy.class)))
                .thenReturn(agent);
        when(strategy.buildConfig(
                org.mockito.ArgumentMatchers.eq(turn.threadId()), org.mockito.ArgumentMatchers.eq("E:/tenant-a"),
                org.mockito.ArgumentMatchers.eq(emitter), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(com.wzx.babiq.server.agent.AgentRunPolicy.class)))
                .thenReturn(config);
        when(runtime.prepare(org.mockito.ArgumentMatchers.any())).thenReturn(
                ContextWindowRuntimeResult.prepared("snapshot-a", "input", "input"));
        when(agent.stream("input", config)).thenReturn(Flux.just(output));
        when(strategy.extractAssistantMessage(output))
                .thenReturn(new org.springframework.ai.chat.messages.AssistantMessage("done"));

        PreparedAttachment newlySelected = attachment(
                "550e8400-e29b-41d4-a716-446655440001", "A-7K3M2Q");
        PreparedAttachment historicalReference = attachment(
                "550e8400-e29b-41d4-a716-446655440002", "A-92CD4F");
        PreparedTurnInput preparedInput = new PreparedTurnInput(
                "input", List.of(newlySelected), List.of(historicalReference));

        loop.invoke(turn, preparedInput, "provider", "E:/tenant-a", emitter, null, null);

        ArgumentCaptor<TurnObservationContext> observation = ArgumentCaptor.forClass(TurnObservationContext.class);
        verify(strategy).buildAgent(
                org.mockito.ArgumentMatchers.eq("provider"), org.mockito.ArgumentMatchers.eq("E:/tenant-a"),
                org.mockito.ArgumentMatchers.eq(emitter), observation.capture(),
                org.mockito.ArgumentMatchers.nullable(com.wzx.babiq.server.agent.AgentRunPolicy.class));
        assertThat(observation.getValue().businessIdentityScope()).isEqualTo(tenantA);
        ArgumentCaptor<ContextWindowRuntimeInput> runtimeInput = ArgumentCaptor.forClass(ContextWindowRuntimeInput.class);
        verify(runtime).prepare(runtimeInput.capture());
        assertThat(runtimeInput.getValue().businessIdentityScope()).isEqualTo(tenantA);
        assertThat(runtimeInput.getValue().userText()).isEqualTo("input");
        ArgumentCaptor<com.wzx.babiq.server.conversation.items.ThreadItem> userItem =
                ArgumentCaptor.forClass(com.wzx.babiq.server.conversation.items.ThreadItem.class);
        verify(emitter, org.mockito.Mockito.atLeastOnce()).emitItemAdded(userItem.capture());
        UserMessageItem emittedUserMessage = userItem.getAllValues().stream()
                .filter(UserMessageItem.class::isInstance)
                .map(UserMessageItem.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(emittedUserMessage).satisfies(item -> {
            assertThat(item.text()).isEqualTo("input");
            assertThat(item.attachments()).containsExactly(newlySelected.metadata());
            assertThat(item.attachments()).doesNotContain(historicalReference.metadata());
        });
    }

    @Test
    void springSelectsTheScopeAwareTurnStartConstructor() {
        Constructor<?>[] constructors = TurnStartHandler.class.getConstructors();

        assertThat(constructors)
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .contains(BusinessIdentityScopeService.class));
    }

    @Test
    void turnExecutorWorkerReceivesTheImmutableTenantAScope() {
        AgentLoop loop = mock(AgentLoop.class);
        Turn tenantATurn = new Turn("turn-worker-a", "thread-worker-a", tenantA);
        ItemEmitter emitter = mock(ItemEmitter.class);
        PreparedTurnInput preparedInput = new PreparedTurnInput("input", List.of(), List.of());

        try (TurnExecutor executor = new TurnExecutor(loop)) {
            executor.submit(tenantATurn, preparedInput, "provider", "E:/tenant-a", emitter, null, null);

            verify(loop, timeout(2_000)).invoke(
                    org.mockito.ArgumentMatchers.argThat(turn ->
                            turn.businessIdentityScope().equals(tenantA)),
                    org.mockito.ArgumentMatchers.same(preparedInput),
                    org.mockito.ArgumentMatchers.eq("provider"),
                    org.mockito.ArgumentMatchers.eq("E:/tenant-a"),
                    org.mockito.ArgumentMatchers.eq(emitter),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull());
        }
    }

    @Test
    void string_compatibility_entry_points_keep_their_exact_pre_attachment_jvm_descriptors()
            throws Exception {
        assertThat(TurnExecutor.class.getMethod(
                "submit",
                Turn.class,
                String.class,
                String.class,
                String.class,
                ItemEmitter.class,
                com.wzx.babiq.server.agent.AgentRunPolicy.class,
                String.class)).isNotNull();
        assertThat(AgentLoop.class.getMethod(
                "invoke",
                Turn.class,
                String.class,
                String.class,
                String.class,
                ItemEmitter.class,
                com.wzx.babiq.server.agent.AgentRunPolicy.class,
                String.class)).isNotNull();
    }

    @Test
    void business_attachment_lookup_history_validation_and_turn_start_stay_inside_connection_lock() {
        ConversationService conversations = mock(ConversationService.class);
        TurnExecutor executor = mock(TurnExecutor.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        AttachmentPreparationService preparationService = mock(AttachmentPreparationService.class);
        AttachmentHistoryResolver historyResolver = mock(AttachmentHistoryResolver.class);
        WebSocketSession session = mock(WebSocketSession.class);
        Thread thread = new Thread("thread-a", "E:/tenant-a", Instant.EPOCH, tenantA);
        Turn turn = new Turn("turn-a", thread.id(), tenantA);
        PreparedTurnInput prepared = new PreparedTurnInput("read A-7K3M2Q", List.of(), List.of());
        AtomicBoolean insideCriticalSection = new AtomicBoolean(false);
        when(scopes.resolve(session)).thenReturn(tenantA);
        when(scopes.withActiveConnectionScope(
                org.mockito.ArgumentMatchers.eq(tenantA),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<BusinessIdentityScopeService.ActiveBusinessIdentity, Object> operation =
                    invocation.getArgument(1);
            insideCriticalSection.set(true);
            try {
                return Optional.ofNullable(operation.apply(
                        new BusinessIdentityScopeService.ActiveBusinessIdentity(
                                mock(com.wzx.babiq.server.application.auth.TrustedDesktopConnection.class),
                                mock(com.wzx.babiq.server.application.auth.TrustedBusinessIdentity.class))));
            } finally {
                insideCriticalSection.set(false);
            }
        });
        when(conversations.findThread(thread.id(), tenantA)).thenAnswer(invocation -> {
            assertThat(insideCriticalSection).isTrue();
            return Optional.of(thread);
        });
        when(preparationService.prepareNew(
                org.mockito.ArgumentMatchers.eq("read A-7K3M2Q"),
                org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            assertThat(insideCriticalSection).isTrue();
            return prepared;
        });
        when(historyResolver.resolve(thread.id(), tenantA, prepared)).thenAnswer(invocation -> {
            assertThat(insideCriticalSection).isTrue();
            return prepared;
        });
        when(conversations.startTurn(thread.id(), tenantA)).thenAnswer(invocation -> {
            assertThat(insideCriticalSection).isTrue();
            return turn;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(insideCriticalSection).isTrue();
            assertThat(invocation.getArgument(1, String.class)).isEqualTo("read A-7K3M2Q");
            return null;
        }).when(conversations).persistTurnStarted(
                org.mockito.ArgumentMatchers.eq(turn),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(thread.cwd()),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        when(conversations.transitionPreExecutionToRunning(turn, com.wzx.babiq.server.conversation.TurnStatus.CREATED))
                .thenAnswer(invocation -> {
                    assertThat(insideCriticalSection).isTrue();
                    turn.start();
                    return true;
                });
        TurnStartHandler handler = new TurnStartHandler(
                conversations, new ObjectMapper(), executor,
                null, null, null, null, null, scopes,
                preparationService, historyResolver);

        handler.handle(
                new ObjectMapper().valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "read A-7K3M2Q"))),
                session);

        verify(executor).submit(
                org.mockito.ArgumentMatchers.eq(turn),
                org.mockito.ArgumentMatchers.same(prepared),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(thread.cwd()),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull());
    }

    private JsonRpcException catchStartFailure(
            TurnStartHandler handler,
            Object params,
            WebSocketSession session) {
        AtomicReference<JsonRpcException> failure = new AtomicReference<>();
        assertThatThrownBy(() -> handler.handle(new ObjectMapper().valueToTree(params), session))
                .isInstanceOfSatisfying(JsonRpcException.class, failure::set);
        return failure.get();
    }

    private static String normalizeThreadId(String message, String threadId) {
        return message.replace(threadId, "<threadId>");
    }

    private static PreparedAttachment attachment(String id, String displayId) {
        AttachmentMetadata metadata = new AttachmentMetadata(
                id,
                displayId,
                displayId + ".pdf",
                "C:\\business\\" + displayId + ".pdf",
                "application/pdf",
                42,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(
                metadata,
                Path.of(metadata.localPath()),
                new PreparedAttachment.FileIdentity(
                        metadata.sizeBytes(), FileTime.from(Instant.EPOCH), "file-key-" + displayId));
    }
}
