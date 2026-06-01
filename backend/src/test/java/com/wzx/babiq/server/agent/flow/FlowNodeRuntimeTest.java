package com.wzx.babiq.server.agent.flow;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlowNodeRuntimeTest {

    @Test
    void flow_write_node_should_preserve_workspace_sandbox_in_child_context() {
        TurnObservationContext observation = TurnObservationContext.start(
                "thr_flow", "turn_flow", "deepseek", "deepseek-v4-pro");
        RunnableConfig parentConfig = RunnableConfig.builder()
                .threadId("thr_flow")
                .addMetadata(TurnObservationContext.METADATA_KEY, observation)
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_CWD, "H:\\aaa")
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.WORKSPACE_WRITE.name())
                .build();
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_flow",
                "dlg_flow",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                "writer",
                BabiqAgentMode.WORKSPACE_TOOL,
                null,
                observation);
        ToolContext original = new ToolContext(Map.of(SubAgentRuntimeFactory.AGENT_CONFIG_KEY, parentConfig));

        ToolContext enriched = SubAgentRuntimeFactory.withDelegationContext(
                original,
                delegation,
                SandboxMode.WORKSPACE_WRITE);

        assertThat(enriched.getContext()).containsEntry(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, "WORKSPACE_WRITE");
        RunnableConfig childConfig = (RunnableConfig) enriched.getContext().get(SubAgentRuntimeFactory.AGENT_CONFIG_KEY);
        assertThat(childConfig.metadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE)).contains("WORKSPACE_WRITE");
        assertThat(childConfig.metadata(SubAgentDelegationContext.METADATA_KEY)).contains(delegation);
    }
}
