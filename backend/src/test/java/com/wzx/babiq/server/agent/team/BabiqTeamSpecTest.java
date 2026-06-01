package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 团队协作冻结规格测试。
 *
 * <p>P6-3 的团队协作和 P6-2 流程一样，必须在启动前冻结成员、工具白名单、
 * 写入范围和沙箱模式。这里先用测试固定规格对象的边界，避免后续实现时把
 * “可修改的模型输入”直接传给官方 StateGraph。</p>
 */
class BabiqTeamSpecTest {

    @Test
    void spec_should_sort_members_and_reject_duplicate_names() {
        BabiqTeamMember second = member("reviewer", 2, BabiqAgentMode.READ_ONLY_TOOL);
        BabiqTeamMember first = member("explorer", 1, BabiqAgentMode.READ_ONLY_TOOL);

        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_1",
                "团队分析",
                "阅读项目并给出建议",
                List.of(second, first),
                4,
                false,
                false,
                SandboxMode.READ_ONLY);

        assertThat(spec.members()).extracting(BabiqTeamMember::name)
                .containsExactly("explorer", "reviewer");
        assertThatThrownBy(() -> new BabiqTeamSpec(
                "team_dup",
                "重复成员",
                "检查重复",
                List.of(member("explorer", 1, BabiqAgentMode.READ_ONLY_TOOL),
                        member("explorer", 2, BabiqAgentMode.READ_ONLY_TOOL)),
                4,
                false,
                false,
                SandboxMode.READ_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");
    }

    @Test
    void approve_once_should_freeze_members_and_preserve_effective_sandbox() {
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_write",
                "团队写入",
                "生成报告",
                List.of(member("writer", 1, BabiqAgentMode.WORKSPACE_TOOL)),
                3,
                false,
                false,
                SandboxMode.READ_ONLY);

        BabiqTeamSpec approved = spec.approvedAndFrozen(SandboxMode.WORKSPACE_WRITE);

        assertThat(approved.approved()).isTrue();
        assertThat(approved.frozen()).isTrue();
        assertThat(approved.sandboxMode()).isEqualTo(SandboxMode.WORKSPACE_WRITE);
        assertThat(approved.requiresWriteAccess()).isTrue();
        assertThatThrownBy(() -> approved.withMembers(List.of(member("extra", 2, BabiqAgentMode.READ_ONLY_TOOL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冻结");
    }

    private BabiqTeamMember member(String name, int order, BabiqAgentMode mode) {
        return new BabiqTeamMember(
                "member_" + name,
                name,
                name,
                name,
                "完成 " + name + " 的任务",
                mode == BabiqAgentMode.WORKSPACE_TOOL ? List.of("write_file") : List.of("read_file", "list_dir"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                mode,
                order,
                name + "_output",
                mode == BabiqAgentMode.WORKSPACE_TOOL ? List.of("H:\\aaa\\report.md") : List.of());
    }
}
