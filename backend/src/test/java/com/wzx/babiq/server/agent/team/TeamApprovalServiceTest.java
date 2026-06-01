package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 团队协作 approve-once 语义测试。
 *
 * <p>团队协作会一次性启动多个成员，审批弹窗必须展示完整成员、工具和写入范围。
 * 审批后规格被冻结，真正工具调用仍由现有沙箱拦截器逐次校验。</p>
 */
class TeamApprovalServiceTest {

    @Test
    void build_scope_should_describe_members_tools_and_write_scopes() {
        TeamApprovalService service = new TeamApprovalService();
        BabiqTeamSpec spec = spec(SandboxMode.WORKSPACE_WRITE);

        TeamApprovalScope scope = service.buildScope(spec);

        assertThat(scope.requiresApproval()).isTrue();
        assertThat(scope.members()).containsExactly("explorer", "writer");
        assertThat(scope.tools()).contains("read_file", "write_file");
        assertThat(scope.writeScopes()).contains("H:\\aaa\\report.md");
        assertThat(scope.description())
                .contains("团队：团队协作")
                .contains("explorer")
                .contains("writer")
                .contains("运行轮数：5");
    }

    @Test
    void validate_write_scopes_should_reject_paths_outside_workspace() {
        TeamApprovalService service = new TeamApprovalService();
        BabiqTeamSpec spec = spec(SandboxMode.WORKSPACE_WRITE);

        assertThatThrownBy(() -> service.validateWriteScopes(
                spec.withMembers(List.of(writer("C:\\outside\\report.md"))),
                Path.of("H:\\aaa")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("团队写入范围");
    }

    private BabiqTeamSpec spec(SandboxMode sandboxMode) {
        return new BabiqTeamSpec(
                "team_scope",
                "团队协作",
                "查看并写入报告",
                List.of(explorer(), writer("H:\\aaa\\report.md")),
                5,
                false,
                false,
                sandboxMode);
    }

    private BabiqTeamMember explorer() {
        return new BabiqTeamMember(
                "member_explorer",
                "explorer",
                "探索成员",
                "explorer",
                "读取上下文",
                List.of("read_file", "list_dir"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                1,
                "explorer_output",
                List.of());
    }

    private BabiqTeamMember writer(String writeScope) {
        return new BabiqTeamMember(
                "member_writer",
                "writer",
                "写入成员",
                "writer",
                "写入报告",
                List.of("write_file"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.WORKSPACE_TOOL,
                2,
                "writer_output",
                List.of(writeScope));
    }
}
