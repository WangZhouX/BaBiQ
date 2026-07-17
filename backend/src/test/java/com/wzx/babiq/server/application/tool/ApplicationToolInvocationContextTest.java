package com.wzx.babiq.server.application.tool;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 application_action 只在真实下游调用期间获得不可变关联上下文。 */
class ApplicationToolInvocationContextTest {

    private final ToolObservationInterceptor interceptor = new ToolObservationInterceptor(new BaBiQMetrics());

    @Test
    void applicationActionInstallsCorrelationAndImmutableIdentityDuringDownstreamCall() {
        BusinessIdentityScope identity = identity("tenant-a", 7);
        ToolCallRequest request = request("call-a", "thread-a", "turn-a", identity);

        ToolCallResponse response = interceptor.interceptToolCall(request, ignored -> {
            ApplicationToolInvocationContext.Invocation invocation =
                    ApplicationToolInvocationContext.current().orElseThrow();
            assertThat(invocation.toolCallId()).isEqualTo("call-a");
            assertThat(invocation.threadId()).isEqualTo("thread-a");
            assertThat(invocation.turnId()).isEqualTo("turn-a");
            assertThat(invocation.businessIdentityScope()).isSameAs(identity);
            return success("call-a");
        });

        assertThat(response.getResult()).isEqualTo("ok");
        assertThat(ApplicationToolInvocationContext.current()).isEmpty();
    }

    @Test
    void applicationActionClearsCorrelationWhenDownstreamFails() {
        ToolCallRequest request = request("call-failure", "thread-a", "turn-a", identity("tenant-a", 7));

        assertThatThrownBy(() -> interceptor.interceptToolCall(request, ignored -> {
            assertThat(ApplicationToolInvocationContext.current()).isPresent();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ApplicationToolInvocationContext.current()).isEmpty();
    }

    @Test
    void nestedApplicationActionsRestoreTheOuterInvocation() {
        BusinessIdentityScope outerIdentity = identity("tenant-a", 7);
        BusinessIdentityScope innerIdentity = identity("tenant-b", 8);
        ToolCallRequest outer = request("call-outer", "thread-outer", "turn-outer", outerIdentity);
        ToolCallRequest inner = request("call-inner", "thread-inner", "turn-inner", innerIdentity);

        interceptor.interceptToolCall(outer, ignored -> {
            assertCurrent("call-outer", outerIdentity);
            interceptor.interceptToolCall(inner, nested -> {
                assertCurrent("call-inner", innerIdentity);
                return success("call-inner");
            });
            assertCurrent("call-outer", outerIdentity);
            return success("call-outer");
        });

        assertThat(ApplicationToolInvocationContext.current()).isEmpty();
    }

    @Test
    void concurrentApplicationActionsKeepThreadLocalCorrelationIsolated() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> observeConcurrentInvocation(
                    request("call-a", "thread-a", "turn-a", identity("tenant-a", 7)), bothEntered, release));
            Future<String> second = executor.submit(() -> observeConcurrentInvocation(
                    request("call-b", "thread-b", "turn-b", identity("tenant-b", 8)), bothEntered, release));

            bothEntered.await();
            release.countDown();

            assertThat(first.get()).isEqualTo("call-a/thread-a/turn-a/tenant-a");
            assertThat(second.get()).isEqualTo("call-b/thread-b/turn-b/tenant-b");
        }
        assertThat(ApplicationToolInvocationContext.current()).isEmpty();
    }

    @Test
    void otherToolsDoNotInstallApplicationInvocationContext() {
        TurnObservationContext observation = TurnObservationContext.start(
                "thread-a", "turn-a", "provider", "model", identity("tenant-a", 7));
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("read_file")
                .toolCallId("call-read")
                .arguments("{}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, observation))
                .build();

        interceptor.interceptToolCall(request, ignored -> {
            assertThat(ApplicationToolInvocationContext.current()).isEmpty();
            return ToolCallResponse.of("call-read", "read_file", "ok");
        });

        assertThat(ApplicationToolInvocationContext.current()).isEmpty();
    }

    private String observeConcurrentInvocation(ToolCallRequest request,
                                               CountDownLatch bothEntered,
                                               CountDownLatch release) throws Exception {
        return interceptor.interceptToolCall(request, ignored -> {
            ApplicationToolInvocationContext.Invocation invocation =
                    ApplicationToolInvocationContext.current().orElseThrow();
            bothEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                    invocation.toolCallId() + "/" + invocation.threadId() + "/" + invocation.turnId()
                            + "/" + invocation.businessIdentityScope().tenantId());
        }).getResult();
    }

    private ToolCallRequest request(String toolCallId,
                                    String threadId,
                                    String turnId,
                                    BusinessIdentityScope identity) {
        TurnObservationContext observation = TurnObservationContext.start(
                threadId, turnId, "provider", "model", identity);
        return ToolCallRequest.builder()
                .toolName("application_action")
                .toolCallId(toolCallId)
                .arguments("{}")
                .context(Map.of(TurnObservationContext.METADATA_KEY, observation))
                .build();
    }

    private ToolCallResponse success(String toolCallId) {
        return ToolCallResponse.of(toolCallId, "application_action", "ok");
    }

    private void assertCurrent(String expectedToolCallId, BusinessIdentityScope expectedIdentity) {
        assertThat(ApplicationToolInvocationContext.current()).hasValueSatisfying(invocation -> {
            assertThat(invocation.toolCallId()).isEqualTo(expectedToolCallId);
            assertThat(invocation.businessIdentityScope()).isSameAs(expectedIdentity);
        });
    }

    private BusinessIdentityScope identity(String tenantId, long epoch) {
        return BusinessIdentityScope.scoped(
                "desktop-" + tenantId,
                "desktop-session-" + tenantId,
                "auth-" + tenantId,
                epoch,
                "user-" + tenantId,
                tenantId,
                "platform-a");
    }

    @Test
    void applicationActionDoesNotEmitTheGenericCommandExecutionItem() throws Exception {
        com.wzx.babiq.server.conversation.ItemEmitter emitter =
                org.mockito.Mockito.mock(com.wzx.babiq.server.conversation.ItemEmitter.class);
        TurnObservationContext observation = TurnObservationContext.start(
                "thread-a", "turn-a", "provider", "model", identity("tenant-a", 7));
        ToolCallRequest request = ToolCallRequest.builder()
                .toolName("application_action")
                .toolCallId("call-action")
                .arguments("{\"actionId\":\"framework.demo\"}")
                .context(Map.of(
                        TurnObservationContext.METADATA_KEY, observation,
                        com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter))
                .build();

        interceptor.interceptToolCall(request, ignored -> success("call-action"));

        org.mockito.Mockito.verify(emitter, org.mockito.Mockito.never())
                .emitCommandExecution(org.mockito.ArgumentMatchers.any());
    }
}
