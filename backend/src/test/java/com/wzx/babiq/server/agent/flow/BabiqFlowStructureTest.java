package com.wzx.babiq.server.agent.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BabiqFlowStructureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void valid_tree_should_reference_every_node_once_and_flatten_in_depth_first_order() {
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

        structure.validateAgainst(List.of(node("scan", 1), node("write", 2), node("review", 3)));

        assertThat(structure.flattenNodeIds())
                .containsExactly("node_scan", "node_write", "node_review");
    }

    @Test
    void child_group_should_not_contain_another_group() {
        BabiqFlowStructure nestedTooDeep = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(new BabiqFlowStructure.FlowGroup(
                        "g_parallel",
                        BabiqFlowTopology.PARALLEL,
                        List.of(new BabiqFlowStructure.FlowGroup(
                                "g_inner",
                                BabiqFlowTopology.SEQUENTIAL,
                                List.of(new BabiqFlowStructure.FlowNodeRef("node_scan"))))))));

        assertThatThrownBy(() -> nestedTooDeep.validateAgainst(List.of(node("scan", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("组内不能再嵌套组");
    }

    @Test
    void validation_should_reject_unknown_duplicate_and_orphan_nodes() {
        BabiqFlowStructure unknown = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(new BabiqFlowStructure.FlowNodeRef("node_missing"))));

        assertThatThrownBy(() -> unknown.validateAgainst(List.of(node("scan", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");

        BabiqFlowStructure duplicate = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(
                        new BabiqFlowStructure.FlowNodeRef("node_scan"),
                        new BabiqFlowStructure.FlowNodeRef("node_scan"))));

        assertThatThrownBy(() -> duplicate.validateAgainst(List.of(node("scan", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复引用");

        BabiqFlowStructure orphan = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(new BabiqFlowStructure.FlowNodeRef("node_scan"))));

        assertThatThrownBy(() -> orphan.validateAgainst(List.of(node("scan", 1), node("write", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未被结构引用");
    }

    @Test
    void parallel_and_routing_groups_should_require_at_least_two_children() {
        BabiqFlowStructure structure = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
                "g_root",
                BabiqFlowTopology.PARALLEL,
                List.of(new BabiqFlowStructure.FlowNodeRef("node_scan"))));

        assertThatThrownBy(() -> structure.validateAgainst(List.of(node("scan", 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少两个子项");
    }

    @Test
    void from_legacy_should_upgrade_flat_spec_to_single_root_group() {
        BabiqFlowStructure structure = BabiqFlowStructure.fromLegacy(
                BabiqFlowTopology.SEQUENTIAL,
                List.of(node("write", 2), node("scan", 1)));

        assertThat(structure.root().groupId()).isEqualTo("g_root");
        assertThat(structure.root().topology()).isEqualTo(BabiqFlowTopology.SEQUENTIAL);
        assertThat(structure.flattenNodeIds()).containsExactly("node_scan", "node_write");
    }

    @Test
    void jackson_should_round_trip_node_refs_and_groups_by_deduction() throws Exception {
        BabiqFlowStructure original = new BabiqFlowStructure(new BabiqFlowStructure.FlowGroup(
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

        String json = objectMapper.writeValueAsString(original);
        BabiqFlowStructure restored = objectMapper.readValue(json, BabiqFlowStructure.class);

        assertThat(json)
                .contains("\"groupId\":\"g_root\"")
                .contains("\"nodeId\":\"node_scan\"");
        assertThat(restored).isEqualTo(original);
    }

    private static BabiqFlowNode node(String name, int order) {
        return new BabiqFlowNode(
                "node_" + name,
                name,
                name,
                name,
                "task " + name,
                List.of("read_file"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                order,
                null,
                name + "_out",
                List.of());
    }
}
