package com.wzx.babiq.server.agent.flow;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowApprovalServiceTest {

    private final FlowApprovalService service = new FlowApprovalService();

    @Test
    void approval_scope_should_describe_nodes_tools_write_paths_and_sandbox() {
        BabiqFlowSpec spec = writeFlow(false, false);

        FlowApprovalScope scope = service.buildScope(spec);

        assertThat(scope.requiresApproval()).isTrue();
        assertThat(scope.description())
                .contains("整理项目")
                .contains("scan -> write")
                .contains("write_file")
                .contains("H:\\aaa")
                .contains("WORKSPACE_WRITE");
    }

    @Test
    void approve_once_should_freeze_flow_without_elevating_sandbox() {
        BabiqFlowSpec approved = service.approveOnce(writeFlow(false, false), SandboxMode.WORKSPACE_WRITE);

        assertThat(approved.approved()).isTrue();
        assertThat(approved.frozen()).isTrue();
        assertThat(approved.sandboxMode()).isEqualTo(SandboxMode.WORKSPACE_WRITE);
        assertThatThrownBy(() -> approved.withNodes(List.of(readNode("scan", 1))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void write_scope_must_stay_inside_current_workspace_when_workspace_sandbox_is_used() {
        BabiqFlowSpec spec = new BabiqFlowSpec(
                "orch_scope",
                "越界写入",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(readNode("scan", 1), writeNode("write", 2, "C:\\Windows")),
                "final",
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        assertThatThrownBy(() -> service.validateWriteScopes(spec, Path.of("H:\\aaa")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("写入范围");
    }

    private static BabiqFlowSpec writeFlow(boolean approved, boolean frozen) {
        return new BabiqFlowSpec(
                "orch_write",
                "整理项目",
                BabiqFlowTopology.SEQUENTIAL,
                List.of(readNode("scan", 1), writeNode("write", 2, "H:\\aaa")),
                "final",
                approved,
                frozen,
                SandboxMode.WORKSPACE_WRITE);
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

    private static BabiqFlowNode writeNode(String name, int order, String writeScope) {
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
                List.of(writeScope));
    }
}
