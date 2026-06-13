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

import java.lang.reflect.Field;
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

    @Test
    void structured_flow_should_compile_nested_parallel_group_recursively() throws Exception {
        FlowOrchestrationService service = new FlowOrchestrationService(
                FlowOrchestrationServiceTest::fakeNodeAgent,
                new FlowApprovalService(),
                mock(ChatModel.class));

        Agent root = service.buildOfficialFlowAgent(
                nestedSpec(),
                new ToolContext(java.util.Map.of()),
                null);

        assertThat(root).isInstanceOf(SequentialAgent.class);
        List<Agent> rootChildren = subAgents(root);
        assertThat(rootChildren).hasSize(2);
        assertThat(rootChildren.get(0).name()).isEqualTo("scan");
        assertThat(rootChildren.get(1)).isInstanceOf(ParallelAgent.class);
        assertThat(rootChildren.get(1).name()).isEqualTo("g_parallel");
        assertThat(subAgents(rootChildren.get(1))).extracting(Agent::name)
                .containsExactly("write", "review");
        assertThat(fieldValue(rootChildren.get(1), "mergeOutputKey")).isEqualTo("g_parallel_output");
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

    private static BabiqFlowSpec nestedSpec() {
        BabiqFlowStructure structure = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(
                        new BabiqFlowStructure.FlowNodeRef("node_scan"),
                        new BabiqFlowStructure.FlowGroup(
                                "g_parallel",
                                BabiqFlowTopology.PARALLEL,
                                List.of(
                                        new BabiqFlowStructure.FlowNodeRef("node_write"),
                                        new BabiqFlowStructure.FlowNodeRef("node_review"))))));
        return new BabiqFlowSpec(
                "orch_nested",
                "nested flow",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(node("scan", 1), node("write", 2), node("review", 3)),
                "final",
                true,
                true,
                SandboxMode.READ_ONLY,
                structure);
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

    @SuppressWarnings("unchecked")
    private static List<Agent> subAgents(Agent agent) throws Exception {
        return (List<Agent>) fieldValue(agent, "subAgents");
    }

    private static Object fieldValue(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
