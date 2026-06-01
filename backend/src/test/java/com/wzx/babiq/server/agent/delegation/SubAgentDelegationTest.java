package com.wzx.babiq.server.agent.delegation;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.AgentDelegationItem;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * explorer 委派入口的行为测试。
 *
 * <p>这组测试刻意不启动完整 ReactAgent，而是把 {@link SubAgentRuntimeFactory}
 * 当作边界 mock 掉，用来钉住父 Agent 可见的协议行为：父聊天流只出现
 * {@code agentDelegation} 聚合 item，子 Agent 内部工具调用不会外泄为普通工具卡片。</p>
 */
class SubAgentDelegationTest {

    @Test
    void explore_should_return_child_summary_and_fold_child_events_into_delegation_item() throws Exception {
        SubAgentRuntimeFactory runtimeFactory = mock(SubAgentRuntimeFactory.class);
        ExplorerSubAgentTool tool = new ExplorerSubAgentTool(runtimeFactory);
        List<ThreadItem> emittedItems = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emittedItems);
        TurnObservationContext observation = TurnObservationContext.start(
                "thr_delegate", "turn_delegate", "deepseek", "deepseek-v4-pro");
        ToolContext toolContext = new ToolContext(Map.of(
                BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter,
                TurnObservationContext.METADATA_KEY, observation));
        when(runtimeFactory.delegate(eq(BuiltInSubAgents.explorer()), eq("检查 README"), eq(toolContext), any()))
                .thenAnswer(invocation -> {
                    SubAgentDelegationContext delegation = invocation.getArgument(3);
                    delegation.recordChildToolCall("read_file");
                    return "README 存在，包含项目说明。";
                });

        String result = tool.explore("检查 README", toolContext);

        assertThat(result).isEqualTo("README 存在，包含项目说明。");
        verify(emitter, never()).emitCommandExecution(any());
        assertThat(emittedItems).hasSize(3);
        assertThat(emittedItems).allSatisfy(item -> assertThat(item).isInstanceOf(AgentDelegationItem.class));
        assertThat(emittedItems)
                .extracting(item -> ((AgentDelegationItem) item).status())
                .containsExactly("running", "running", "completed");
        AgentDelegationItem completed = (AgentDelegationItem) emittedItems.get(2);
        assertThat(completed.parentAgent()).isEqualTo(BuiltInSubAgents.MAIN_AGENT_NAME);
        assertThat(completed.childAgent()).isEqualTo(BuiltInSubAgents.EXPLORER_NAME);
        assertThat(completed.summary()).contains("README");
        assertThat(completed.toolCallCount()).isEqualTo(1);

        ArgumentCaptor<SubAgentDelegationContext> delegationCaptor =
                ArgumentCaptor.forClass(SubAgentDelegationContext.class);
        verify(runtimeFactory).delegate(eq(BuiltInSubAgents.explorer()), eq("检查 README"), eq(toolContext),
                delegationCaptor.capture());
        assertThat(delegationCaptor.getValue().delegationId()).startsWith("dlg_");
    }

    private ItemEmitter capturingEmitter(List<ThreadItem> emittedItems) throws Exception {
        ItemEmitter emitter = mock(ItemEmitter.class);
        doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemAdded(any(ThreadItem.class));
        doAnswer(invocation -> {
            emittedItems.add(invocation.getArgument(0));
            return null;
        }).when(emitter).emitItemUpdated(any(ThreadItem.class));
        return emitter;
    }
}
