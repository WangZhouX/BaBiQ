package com.wzx.babiq.server.agent.team;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 团队记忆工作区测试。
 *
 * <p>P6-3a 要求团队 blackboard 的源记录追加式、永久、完整；测试只使用临时目录，
 * 不写真实用户家目录。</p>
 */
class TeamMemoryWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    void workspace_should_write_team_files_and_persist_artifact_records() throws Exception {
        CapturingTeamRepository repository = new CapturingTeamRepository();
        TeamMemoryWorkspace workspace = new TeamMemoryWorkspace(
                new TeamMemoryProperties(true, tempDir, 3000, 2000, 600, 12),
                new ApproximateContextTokenEstimator(),
                repository);
        BabiqTeamSpec spec = teamSpec();

        workspace.initTeam("team_memory", spec);
        TeamArtifactRecord output = workspace.writeMemberOutput(
                "team_memory",
                2,
                "writer",
                "writer 输出全文：已经修改 index.html 并补充 checklist。");
        workspace.appendIndexEntry("team_memory", 2, "writer", "完成页面修改", Path.of(output.relativePath()));
        TeamArtifactRecord digest = workspace.writeDigest("team_memory", "当前讨论概要：writer 已完成修改。");
        TeamArtifactRecord result = workspace.writeResult("team_memory", "最终结果：P6-3a 团队交付完成。");

        Path teamDir = tempDir.resolve("team_memory");
        assertThat(workspace.teamDir("team_memory")).isEqualTo(teamDir);
        assertThat(Files.readString(teamDir.resolve("team.md")))
                .contains("# 团队：团队记忆测试")
                .contains("目标：验证团队 blackboard")
                .contains("[r2 writer](rounds/r2-writer.md) - 完成页面修改");
        assertThat(Files.readString(teamDir.resolve("rounds").resolve("r2-writer.md")))
                .contains("writer 输出全文");
        assertThat(Files.readString(teamDir.resolve("digest.md"))).contains("当前讨论概要");
        assertThat(Files.readString(teamDir.resolve("result.md"))).contains("最终结果");

        assertArtifact(output, "MEMBER_OUTPUT", "rounds/r2-writer.md", 2, "writer");
        assertArtifact(digest, "DIGEST", "digest.md", 0, null);
        assertArtifact(result, "RESULT", "result.md", 0, null);
        assertThat(repository.artifacts)
                .extracting(TeamArtifactRecord::artifactType)
                .containsExactly("TEAM_INDEX", "MEMBER_OUTPUT", "TEAM_INDEX", "DIGEST", "RESULT");
    }

    private void assertArtifact(TeamArtifactRecord record,
                                String artifactType,
                                String relativePath,
                                int round,
                                String memberName) {
        assertThat(record.artifactId()).startsWith("teamart_");
        assertThat(record.artifactType()).isEqualTo(artifactType);
        assertThat(record.relativePath()).isEqualTo(relativePath);
        assertThat(record.sha256()).hasSize(64);
        assertThat(record.tokenEstimate()).isPositive();
        assertThat(record.round()).isEqualTo(round);
        assertThat(record.memberName()).isEqualTo(memberName);
        assertThat(record.content()).isNotBlank();
    }

    private BabiqTeamSpec teamSpec() {
        return new BabiqTeamSpec(
                "team_memory",
                "团队记忆测试",
                "验证团队 blackboard",
                List.of(new BabiqTeamMember(
                        "member_writer",
                        "writer",
                        "Writer",
                        "writer",
                        "写入总结",
                        List.of("write_file"),
                        BabiqAgentSpec.ModelPolicy.inherit(),
                        BabiqAgentMode.WORKSPACE_TOOL,
                        1,
                        "writer_output",
                        List.of("H:\\aaa\\index.html"))),
                4,
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);
    }

    private static final class CapturingTeamRepository implements TeamRepository {
        private final List<TeamArtifactRecord> artifacts = new ArrayList<>();

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
