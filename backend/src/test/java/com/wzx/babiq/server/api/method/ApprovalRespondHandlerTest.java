package com.wzx.babiq.server.api.method;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ApprovalRespondHandler 测试。
 *
 * <p>覆盖 D23 的手动 ToolFeedback.Builder 构造和 TurnExecutor.resume 提交流程。</p>
 */
class ApprovalRespondHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_builds_approved_feedback_and_submits_resume() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        turn.waitApproval();
        PendingApprovals pendingApprovals = new PendingApprovals();
        pendingApprovals.put(thread.id(), metadata());
        TurnExecutor executor = mock(TurnExecutor.class);
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                pendingApprovals, conversationService, objectMapper, executor);

        Object payload = handler.handle(objectMapper.valueToTree(Map.of(
                "threadId", thread.id(),
                "turnId", turn.id(),
                "decision", "approve")), null);

        assertThat(((Map<?, ?>) payload).get("delivered")).isEqualTo(true);
        assertThat(turn.status()).isEqualTo(TurnStatus.RUNNING);
        verify(executor).submitResume(eq(turn), any(InterruptionMetadata.class), eq("."), any());
    }

    @Test
    void build_feedback_maps_edit_to_edited_arguments() {
        ApprovalRespondHandler handler = new ApprovalRespondHandler(
                new PendingApprovals(), new ConversationService(), objectMapper, mock(TurnExecutor.class));

        InterruptionMetadata feedback = handler.buildFeedback(metadata(), "edit", "{\"path\":\"b.txt\"}");

        InterruptionMetadata.ToolFeedback toolFeedback = feedback.toolFeedbacks().get(0);
        assertThat(toolFeedback.getResult()).isEqualTo(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED);
        assertThat(toolFeedback.getArguments()).isEqualTo("{\"path\":\"b.txt\"}");
    }

    private InterruptionMetadata metadata() {
        InterruptionMetadata.ToolFeedback feedback = InterruptionMetadata.ToolFeedback.builder()
                .id("call_1")
                .name("write_file")
                .arguments("{\"path\":\"a.txt\"}")
                .description("写文件")
                .build();
        return InterruptionMetadata.builder("hitl", new OverAllState())
                .addToolFeedback(feedback)
                .build();
    }
}
