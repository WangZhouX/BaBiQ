package com.wzx.babiq.server.persistence;

import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamMessageRecord;
import com.wzx.babiq.server.agent.team.TeamArtifactRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-3 团队协作持久化测试。
 *
 * <p>team 与 teamMessage 必须进入 SQLite 审计事实源；桌面端右侧面板、历史加载和
 * direct teammate message 都依赖这些记录，而不能只靠内存态。</p>
 */
@SpringBootTest
class TeamRepositoryTest {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "team-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private TeamRepository repository;

    @Test
    void repository_should_persist_team_members_and_timeline_messages() {
        repository.save(new TeamRecord(
                        "team_repo",
                        "thr_repo",
                        "turn_repo",
                        "团队协作",
                        "读取并总结目录",
                        "running",
                        "H:\\aaa",
                        "WORKSPACE_WRITE",
                        true,
                        true,
                        5,
                        1,
                        "explorer",
                        "正在读取目录",
                        null),
                List.of(new TeamMemberRecord(
                        "team_repo",
                        "member_explorer",
                        "explorer",
                        "探索成员",
                        "explorer",
                        "READ_ONLY_TOOL",
                        "read_file,list_dir",
                        "running",
                        1,
                        2,
                        128,
                        "正在读取")));
        repository.saveMessage(new TeamMessageRecord(
                "team_repo",
                "msg_repo_1",
                "thr_repo",
                "turn_repo",
                "supervisor",
                "explorer",
                "route",
                "请先查看当前目录",
                "{\"next\":\"explorer\",\"reason\":\"需要目录信息\",\"confidence\":0.8}",
                1));
        repository.saveArtifact(new TeamArtifactRecord(
                "team_repo",
                "teamart_repo_1",
                "MEMBER_OUTPUT",
                "rounds/r1-explorer.md",
                "0".repeat(64),
                42,
                1,
                "explorer",
                "目录已读取",
                Instant.parse("2026-06-14T00:00:00Z"),
                Instant.parse("2026-06-14T00:00:00Z")));
        repository.updateMember("team_repo", "member_explorer", "completed", 3, 256, "目录已读取");

        Optional<TeamRecord> team = repository.findByTeamId("team_repo");
        List<TeamMemberRecord> members = repository.listMembers("team_repo");
        List<TeamMessageRecord> messages = repository.listMessages("team_repo");
        List<TeamArtifactRecord> artifacts = repository.listArtifacts("team_repo");

        assertThat(team).isPresent();
        assertThat(team.get().currentAgent()).isEqualTo("explorer");
        assertThat(members).hasSize(1);
        assertThat(members.getFirst().status()).isEqualTo("completed");
        assertThat(members.getFirst().toolCallCount()).isEqualTo(3);
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().messageType()).isEqualTo("route");
        assertThat(messages.getFirst().content()).contains("目录");
        assertThat(artifacts).hasSize(1);
        assertThat(artifacts.getFirst().artifactType()).isEqualTo("MEMBER_OUTPUT");
        assertThat(artifacts.getFirst().relativePath()).isEqualTo("rounds/r1-explorer.md");
        assertThat(artifacts.getFirst().content()).contains("目录");
    }
}
