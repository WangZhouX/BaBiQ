package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntime;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeResult;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentLoop 与 ContextWindowRuntime 的接入测试。
 *
 * <p>这里验证的不是 UI chip，而是后端真正传给 ReactAgent 的输入已经替换为运行时生成的
 * 上下文窗口文本，并且模型返回 usage 后能回填快照。</p>
 */
class AgentLoopContextRuntimeTest {

    @Test
    @DisplayName("普通 turn 会先生成上下文窗口再调用 ReactAgent")
    void invoke_should_send_contextual_input_to_agent() throws Exception {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        ContextWindowRuntime runtime = mock(ContextWindowRuntime.class);
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        AgentLoop loop = new AgentLoop(strategy, new PendingApprovals(), summaryEmitter,
                new TurnObservationRegistry(), runtime);
        Turn turn = new Turn("turn_ctx", "thr_ctx");
        turn.start();
        ItemEmitter emitter = mock(ItemEmitter.class);
        RunnableConfig config = RunnableConfig.builder().threadId("thr_ctx").build();

        when(strategy.defaultRunPolicy()).thenReturn(null);
        when(strategy.resolveModelName("provider-a")).thenReturn("deepseek-v4-pro");
        when(strategy.resolveContextWindow("provider-a")).thenReturn(128_000);
        when(strategy.currentToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(strategy.buildAgent(eq("provider-a"), eq("E:\\BaBiQ"), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class))).thenReturn(agent);
        when(strategy.buildConfig(eq("thr_ctx"), eq("E:\\BaBiQ"), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class))).thenReturn(config);
        when(runtime.prepare(any())).thenReturn(ContextWindowRuntimeResult.prepared(
                "ctxsnap_1",
                "原始输入",
                "上下文窗口输入"));
        when(agent.stream(eq("上下文窗口输入"), eq(config))).thenReturn(Flux.just(output));
        when(strategy.extractAssistantMessage(output)).thenReturn(new AssistantMessage("done"));

        loop.invoke(turn, "原始输入", "provider-a", "E:\\BaBiQ", emitter);

        verify(agent).stream(eq("上下文窗口输入"), eq(config));
        verify(runtime).recordUsage(eq("ctxsnap_1"), any(TurnObservationContext.class));
    }
}
