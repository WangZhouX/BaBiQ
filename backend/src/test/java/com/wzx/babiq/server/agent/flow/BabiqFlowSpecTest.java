package com.wzx.babiq.server.agent.flow;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BabiqFlowSpecTest {

    @Test
    void sequential_flow_should_keep_node_order_and_freeze_runtime_shape() {
        BabiqFlowSpec spec = new BabiqFlowSpec(
                "orch_1",
                "整理当前项目",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(readNode("scan", 1), writeNode("write", 2)),
                "final",
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        assertThat(spec.nodes()).extracting(BabiqFlowNode::name).containsExactly("scan", "write");
        assertThat(spec.requiresWriteAccess()).isTrue();
        assertThat(spec.node("write")).contains(writeNode("write", 2));
        assertThatThrownBy(() -> spec.withNodes(List.of(readNode("changed", 1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已冻结");
    }

    @Test
    void flow_should_reject_duplicate_node_names() {
        assertThatThrownBy(() -> new BabiqFlowSpec(
                "orch_2",
                "重复节点",
                BabiqFlowTopology.PARALLEL,
                List.of(readNode("scan", 1), readNode("scan", 2)),
                "final",
                false,
                false,
                SandboxMode.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void parallel_and_routing_flow_should_require_multiple_nodes() {
        assertThatThrownBy(() -> new BabiqFlowSpec(
                "orch_3",
                "单节点并行",
                BabiqFlowTopology.PARALLEL,
                List.of(readNode("only", 1)),
                "final",
                false,
                false,
                SandboxMode.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少两个");
    }

    @Test
    void missing_structure_should_upgrade_to_legacy_flat_root_group() {
        BabiqFlowSpec spec = new BabiqFlowSpec(
                "orch_legacy",
                "legacy flat flow",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(readNode("scan", 2), readNode("plan", 1)),
                "final",
                false,
                false,
                SandboxMode.READ_ONLY);

        assertThat(spec.structure()).isNotNull();
        assertThat(spec.structure().root().topology()).isEqualTo(BabiqFlowTopology.SEQUENTIAL);
        assertThat(spec.structure().flattenNodeIds()).containsExactly("node_plan", "node_scan");
    }

    @Test
    void explicit_structure_should_be_validated_against_nodes() {
        BabiqFlowStructure invalid = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(new BabiqFlowStructure.FlowNodeRef("node_scan"))));

        assertThatThrownBy(() -> new BabiqFlowSpec(
                "orch_structured",
                "structured flow",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(readNode("scan", 1), readNode("write", 2)),
                "final",
                false,
                false,
                SandboxMode.READ_ONLY,
                invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未被结构引用");
    }

    private static BabiqFlowNode readNode(String name, int order) {
        return new BabiqFlowNode(
                "node_" + name,
                name,
                "读取节点",
                "explorer",
                "查看当前目录",
                List.of("read_file", "list_dir"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                order,
                null,
                name + "_out",
                List.of());
    }

    private static BabiqFlowNode writeNode(String name, int order) {
        return new BabiqFlowNode(
                "node_" + name,
                name,
                "写入节点",
                "worker",
                "写入总结文件",
                List.of("write_file"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.WORKSPACE_TOOL,
                order,
                null,
                name + "_out",
                List.of("H:\\aaa"));
    }
}
