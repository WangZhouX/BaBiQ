package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentLoop 单元测试。
 *
 * <p>只验证薄主流程：发 userMessage、调用 ReactAgent、发 agentMessage 并完成 turn。</p>
 */
class AgentLoopTest {

    @Test
    void invoke_emits_user_and_agent_messages() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        PendingApprovals pendingApprovals = new PendingApprovals();
        AgentLoop loop = new AgentLoop(strategy, pendingApprovals);
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);

        when(strategy.buildAgent("provider-a", ".", emitter)).thenReturn(agent);
        when(strategy.buildConfig("thr_1")).thenCallRealMethod();
        when(agent.invokeAndGetOutput(any(String.class), any())).thenReturn(Optional.of(output));
        when(strategy.extractAssistantMessage(output)).thenReturn(new AssistantMessage("done"));

        loop.invoke(turn, "hello", "provider-a", ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(emitted).extracting(ThreadItem::type).containsExactly("userMessage", "agentMessage");
    }

    @Test
    void invoke_marks_turn_failed_when_agent_throws() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        AgentLoop loop = new AgentLoop(strategy, new PendingApprovals());
        Turn turn = new Turn("turn_1", "thr_1");
        turn.start();
        ItemEmitter emitter = mock(ItemEmitter.class);

        when(strategy.buildAgent(null, ".", emitter)).thenReturn(agent);
        when(strategy.buildConfig("thr_1")).thenCallRealMethod();
        when(agent.invokeAndGetOutput(any(String.class), any())).thenThrow(new RuntimeException("model down"));

        loop.invoke(turn, "hello", null, ".", emitter);

        assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(turn.failureReason()).contains("model down");
    }

    private ItemEmitter capturingEmitter(List<ThreadItem> emitted) throws Exception {
        ItemEmitter emitter = mock(ItemEmitter.class);
        doAnswer(invocation -> {
            emitted.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemAdded(any(ThreadItem.class));
        return emitter;
    }
}
