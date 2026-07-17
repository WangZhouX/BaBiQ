package com.wzx.babiq.server.application.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.agent.ReActStrategy;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntime;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeResult;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.api.method.TurnStartHandler;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeInput;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
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
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
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

        loop.invoke(turn, "input", "provider", "E:/tenant-a", emitter);

        ArgumentCaptor<TurnObservationContext> observation = ArgumentCaptor.forClass(TurnObservationContext.class);
        verify(strategy).buildAgent(
                org.mockito.ArgumentMatchers.eq("provider"), org.mockito.ArgumentMatchers.eq("E:/tenant-a"),
                org.mockito.ArgumentMatchers.eq(emitter), observation.capture(),
                org.mockito.ArgumentMatchers.nullable(com.wzx.babiq.server.agent.AgentRunPolicy.class));
        assertThat(observation.getValue().businessIdentityScope()).isEqualTo(tenantA);
        ArgumentCaptor<ContextWindowRuntimeInput> runtimeInput = ArgumentCaptor.forClass(ContextWindowRuntimeInput.class);
        verify(runtime).prepare(runtimeInput.capture());
        assertThat(runtimeInput.getValue().businessIdentityScope()).isEqualTo(tenantA);
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

        try (TurnExecutor executor = new TurnExecutor(loop)) {
            executor.submit(tenantATurn, "input", "provider", "E:/tenant-a", emitter, null, null);

            verify(loop, timeout(2_000)).invoke(
                    org.mockito.ArgumentMatchers.argThat(turn ->
                            turn.businessIdentityScope().equals(tenantA)),
                    org.mockito.ArgumentMatchers.eq("input"),
                    org.mockito.ArgumentMatchers.eq("provider"),
                    org.mockito.ArgumentMatchers.eq("E:/tenant-a"),
                    org.mockito.ArgumentMatchers.eq(emitter),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull());
        }
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
}
