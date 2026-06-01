package com.wzx.babiq.server.agent.flow;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.ParallelAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowOrchestrationServiceTest {

    @Test
    void official_flow_agent_should_match_declared_topology() {
        FlowOrchestrationService service = new FlowOrchestrationService(
                FlowOrchestrationServiceTest::fakeNodeAgent,
                new FlowApprovalService(),
                mock(ChatModel.class));

        assertThat(service.buildOfficialFlowAgent(spec(BabiqFlowTopology.SEQUENTIAL), new ToolContext(java.util.Map.of()), null))
                .isInstanceOf(SequentialAgent.class);
        assertThat(service.buildOfficialFlowAgent(spec(BabiqFlowTopology.PARALLEL), new ToolContext(java.util.Map.of()), null))
                .isInstanceOf(ParallelAgent.class);
        assertThat(service.buildOfficialFlowAgent(spec(BabiqFlowTopology.ROUTING), new ToolContext(java.util.Map.of()), null))
                .isInstanceOf(LlmRoutingAgent.class);
    }

    private static Agent fakeNodeAgent(BabiqFlowNode node, ToolContext toolContext) {
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.name()).thenReturn(node.name());
        when(agent.description()).thenReturn(node.task());
        when(agent.getOutputKey()).thenReturn(node.outputKey());
        return agent;
    }

    private static BabiqFlowSpec spec(BabiqFlowTopology topology) {
        return new BabiqFlowSpec(
                "orch_" + topology.name().toLowerCase(),
                topology.name(),
                topology,
                List.of(node("a", 1), node("b", 2)),
                "final",
                true,
                true,
                SandboxMode.READ_ONLY);
    }

    private static BabiqFlowNode node(String name, int order) {
        return new BabiqFlowNode(
                "node_" + name,
                name,
                name,
                "explorer",
                "查看信息",
                List.of("read_file"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                order,
                null,
                name + "_out",
                List.of());
    }
}
