package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.approval.ApprovalRuleService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.hook.BaBiQTokenUsageHook;
import com.wzx.babiq.server.hook.ResumeJumpCleanupHook;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.interceptor.BaBiQStreamingTokenUsageInterceptor;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证含图片字节的 SAA checkpoint 只在 HITL 等待期间保留，所有终态都会释放。
 */
class AgentCheckpointLifecycleTest {

    @Test
    void ordinary_completion_releases_media_checkpoint() throws Exception {
        Fixture fixture = fixture("thr_complete");
        fixture.seedMediaCheckpoint();
        Turn turn = fixture.startedTurn("turn_complete");

        fixture.handler.handleOutput(
                turn,
                fixture.emitter,
                new AgentStreamConsumer.StreamResult(Optional.empty(), "assistant_1", "done"),
                fixture.context(turn),
                ".",
                fixture.agent,
                fixture.runPolicy);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        fixture.assertCheckpointReleased();
    }

    @Test
    void terminal_failure_releases_media_checkpoint() throws Exception {
        Fixture fixture = fixture("thr_failure");
        fixture.seedMediaCheckpoint();
        Turn turn = fixture.startedTurn("turn_failure");

        fixture.handler.invokeResume(
                turn,
                approvedFeedback(),
                ".",
                fixture.emitter,
                fixture.runPolicy);

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        fixture.assertCheckpointReleased();
    }

    @Test
    void cancel_forget_paused_releases_media_checkpoint() throws Exception {
        Fixture fixture = fixture("thr_cancel");
        fixture.seedMediaCheckpoint();

        fixture.handler.forgetPaused(fixture.threadId);

        fixture.assertCheckpointReleased();
    }

    @Test
    void waiting_approval_keeps_media_checkpoint_for_resume() throws Exception {
        Fixture fixture = fixture("thr_waiting");
        fixture.seedMediaCheckpoint();
        Turn turn = fixture.startedTurn("turn_waiting");

        fixture.handler.handleOutput(
                turn,
                fixture.emitter,
                interruptionResult(pendingFeedback()),
                fixture.context(turn),
                ".",
                fixture.agent,
                fixture.runPolicy);

        assertThat(turn.status()).isEqualTo(TurnStatus.WAITING_APPROVAL);
        fixture.assertCheckpointRetained();
    }

    @Test
    void hitl_final_resume_releases_media_checkpoint() throws Exception {
        Fixture fixture = fixture("thr_resume");
        fixture.seedMediaCheckpoint();
        Turn turn = fixture.startedTurn("turn_resume");
        InterruptionMetadata pending = pendingFeedback();
        fixture.handler.handleOutput(
                turn,
                fixture.emitter,
                interruptionResult(pending),
                fixture.context(turn),
                ".",
                fixture.agent,
                fixture.runPolicy);
        when(fixture.agent.stream(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Flux.just(new StreamingOutput<>(
                        new AssistantMessage("done"),
                        "mock-model",
                        "babiq_agent",
                        new OverAllState())));

        fixture.handler.invokeResume(
                turn,
                approvedFeedback(pending),
                ".",
                fixture.emitter,
                fixture.runPolicy);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        fixture.assertCheckpointReleased();
    }

    private static AgentStreamConsumer.StreamResult interruptionResult(
            InterruptionMetadata metadata
    ) {
        return new AgentStreamConsumer.StreamResult(Optional.of(metadata), null, "");
    }

    private static InterruptionMetadata pendingFeedback() {
        InterruptionMetadata.ToolFeedback feedback = InterruptionMetadata.ToolFeedback.builder()
                .id("call_1")
                .name("write_file")
                .arguments("{\"path\":\"hello.txt\"}")
                .build();
        return InterruptionMetadata.builder("hitl", new OverAllState())
                .addToolFeedback(feedback)
                .build();
    }

    private static InterruptionMetadata approvedFeedback() {
        return approvedFeedback(pendingFeedback());
    }

    private static InterruptionMetadata approvedFeedback(InterruptionMetadata pending) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(pending);
        builder.toolFeedbacks(List.of());
        for (InterruptionMetadata.ToolFeedback feedback : pending.toolFeedbacks()) {
            builder.addToolFeedback(InterruptionMetadata.ToolFeedback.builder(feedback)
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                    .build());
        }
        return builder.build();
    }

    private static Fixture fixture(String threadId) throws Exception {
        ChatClientFactory chatClientFactory = mock(ChatClientFactory.class);
        AgentLoopProperties properties = new AgentLoopProperties(
                20,
                ApprovalPolicy.ON_REQUEST,
                SandboxMode.WORKSPACE_WRITE,
                List.of(),
                null);
        ReActStrategy strategy = new ReActStrategy(
                chatClientFactory,
                mock(ToolRegistry.class),
                properties,
                mock(BaBiQSandboxInterceptor.class),
                mock(ToolObservationInterceptor.class),
                mock(SpotlightingToolInterceptor.class),
                mock(BaBiQTokenUsageHook.class),
                mock(ResumeJumpCleanupHook.class),
                mock(BaBiQStreamingTokenUsageInterceptor.class),
                mock(ApprovalRuleService.class),
                mock(TurnPersistenceService.class));
        when(chatClientFactory.resolveModelName(null)).thenReturn("mock-model");
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        TurnObservationRegistry observations = new TurnObservationRegistry();
        AgentLoopOutputHandler handler = new AgentLoopOutputHandler(
                strategy,
                new PendingApprovals(),
                summaryEmitter,
                observations);
        Field saverField = ReActStrategy.class.getDeclaredField("memorySaver");
        saverField.setAccessible(true);
        MemorySaver saver = (MemorySaver) saverField.get(strategy);
        return new Fixture(
                threadId,
                strategy,
                handler,
                saver,
                RunnableConfig.builder().threadId(threadId).build(),
                mock(ReactAgent.class),
                mock(ItemEmitter.class),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
    }

    private record Fixture(
            String threadId,
            ReActStrategy strategy,
            AgentLoopOutputHandler handler,
            MemorySaver saver,
            RunnableConfig config,
            ReactAgent agent,
            ItemEmitter emitter,
            AgentRunPolicy runPolicy
    ) {
        private Turn startedTurn(String turnId) {
            Turn turn = new Turn(turnId, threadId);
            turn.start();
            return turn;
        }

        private TurnObservationContext context(Turn turn) {
            return TurnObservationContext.start(
                    turn.threadId(), turn.id(), "provider", "mock-model");
        }

        private void seedMediaCheckpoint() throws Exception {
            byte[] imageBytes = new byte[]{9, 8, 7, 6};
            Media media = Media.builder()
                    .mimeType(MimeTypeUtils.IMAGE_PNG)
                    .data(imageBytes)
                    .name("")
                    .build();
            UserMessage message = UserMessage.builder()
                    .text("inspect image")
                    .media(media)
                    .build();
            saver.put(
                    config,
                    Checkpoint.builder()
                            .id("checkpoint-" + threadId)
                            .state(Map.of("messages", List.of(message)))
                            .nodeId("model")
                            .nextNodeId("model")
                            .build());
            assertThat(saver.list(config)).singleElement().satisfies(checkpoint -> {
                List<?> messages = (List<?>) checkpoint.getState().get("messages");
                assertThat(messages).hasSize(1);
                assertThat(messages.getFirst()).isEqualTo(message);
                assertThat(((UserMessage) messages.getFirst()).getMedia())
                        .singleElement()
                        .satisfies(storedMedia ->
                                assertThat(storedMedia.getDataAsByteArray())
                                        .containsExactly(imageBytes));
            });
        }

        private void assertCheckpointRetained() {
            assertThat(saver.list(config)).hasSize(1);
        }

        private void assertCheckpointReleased() {
            assertThat(saver.list(config)).isEmpty();
        }
    }
}
