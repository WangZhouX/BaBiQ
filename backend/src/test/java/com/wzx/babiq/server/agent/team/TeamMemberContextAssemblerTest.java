package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队成员上下文装配测试。
 *
 * <p>成员每轮无状态重建上下文，只能看到团队目标、职责与本轮理由、滚动概要、索引引用；
 * `rounds/*.md` 全文必须由成员按需 read_file 拉取，不能被直接 push 进模型窗口。</p>
 */
class TeamMemberContextAssemblerTest {

    @TempDir
    Path tempDir;

    @Test
    void assembleMemberInstruction_should_include_bounded_four_blocks_without_round_full_text() {
        CapturingTeamRepository repository = new CapturingTeamRepository();
        TeamMemoryWorkspace workspace = new TeamMemoryWorkspace(
                new TeamMemoryProperties(true, tempDir, 3000, 2000, 600, 12),
                new ApproximateContextTokenEstimator(),
                repository);
        BabiqTeamSpec spec = teamSpec();
        workspace.initTeam(spec.teamId(), spec);
        TeamArtifactRecord detail = workspace.writeMemberOutput(
                spec.teamId(),
                1,
                "writer",
                "SECRET_FULL_TEXT: writer 输出全文不应该直接进入成员上下文。");
        workspace.appendIndexEntry(spec.teamId(), 1, "writer", "writer 已完成初稿", Path.of(detail.relativePath()));
        workspace.writeDigest(spec.teamId(), "滚动概要：writer 已完成初稿，等待 reviewer 复核。");
        TeamMemberContextAssembler assembler = new TeamMemberContextAssembler(workspace);

        String instruction = assembler.assembleMemberInstruction(
                spec,
                spec.member("reviewer").orElseThrow(),
                2,
                "需要复核 writer 的产物",
                List.of(new TeamMessageRecord(
                        spec.teamId(),
                        "msg_direct",
                        "thread_1",
                        "turn_1",
                        "user",
                        "reviewer",
                        "direct_user",
                        "请重点检查 HTML 路径。",
                        null,
                        2)));

        assertThat(instruction)
                .contains("团队目标")
                .contains("验证成员读路径")
                .contains("本职任务")
                .contains("复核 writer 的输出")
                .contains("supervisor 理由")
                .contains("需要复核 writer 的产物")
                .contains("滚动讨论概要")
                .contains("writer 已完成初稿")
                .contains("team.md 索引")
                .contains("rounds/r1-writer.md")
                .contains("请重点检查 HTML 路径。")
                .doesNotContain("SECRET_FULL_TEXT");
    }

    private BabiqTeamSpec teamSpec() {
        return new BabiqTeamSpec(
                "team_context",
                "团队上下文",
                "验证成员读路径",
                List.of(
                        member("writer", "写入初稿", 1),
                        member("reviewer", "复核 writer 的输出", 2)),
                4,
                true,
                true,
                SandboxMode.READ_ONLY);
    }

    private BabiqTeamMember member(String name, String task, int order) {
        return new BabiqTeamMember(
                "member_" + name,
                name,
                name,
                name,
                task,
                List.of("read_file"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                order,
                name + "_output",
                List.of());
    }

    private static final class CapturingTeamRepository implements TeamRepository {
        private final List<TeamArtifactRecord> artifacts = new java.util.ArrayList<>();

        @Override
        public void save(TeamRecord record, List<TeamMemberRecord> members) {
        }

        @Override
        public void updateMember(String teamId, String memberId, String status,
                                 int toolCallCount, int tokenEstimate, String summary) {
        }

        @Override
        public void saveMessage(TeamMessageRecord message) {
        }

        @Override
        public void saveArtifact(TeamArtifactRecord artifact) {
            artifacts.add(artifact);
        }

        @Override
        public Optional<TeamRecord> findByTeamId(String teamId) {
            return Optional.empty();
        }

        @Override
        public List<TeamMemberRecord> listMembers(String teamId) {
            return List.of();
        }

        @Override
        public List<TeamMessageRecord> listMessages(String teamId) {
            return List.of();
        }

        @Override
        public List<TeamArtifactRecord> listArtifacts(String teamId) {
            return artifacts.stream()
                    .filter(artifact -> artifact.teamId().equals(teamId))
                    .toList();
        }
    }
}
