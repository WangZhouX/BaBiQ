package com.wzx.babiq.server.persistence;

import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamMessageRecord;
import com.wzx.babiq.server.agent.team.TeamArtifactRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import com.wzx.babiq.server.conversation.ConversationEventRecorder;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.TeamItem;
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

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationEventRecorder recorder;

    @Autowired
    private ConversationApplicationService applicationService;

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

    @Test
    void list_by_thread_should_skip_team_when_runtime_item_was_removed() {
        Thread thread = conversationService.createThread("H:\\aaa");
        Turn turn = conversationService.startTurn(thread.id(), "run teams", "deepseek", "deepseek-v4-pro",
                "H:\\aaa", "DANGER_FULL_ACCESS", "ON_REQUEST");
        repository.save(new TeamRecord(
                        "team_visible",
                        thread.id(),
                        turn.id(),
                        "可见团队",
                        "继续展示",
                        "completed",
                        "H:\\aaa",
                        "WORKSPACE_WRITE",
                        true,
                        true,
                        5,
                        1,
                        null,
                        "done",
                        null),
                List.of());
        repository.save(new TeamRecord(
                        "team_hidden",
                        thread.id(),
                        turn.id(),
                        "已移除团队",
                        "不再展示",
                        "failed",
                        "H:\\aaa",
                        "WORKSPACE_WRITE",
                        true,
                        true,
                        5,
                        1,
                        null,
                        null,
                        "removed by user"),
                List.of());
        recorder.recordItemAdded(thread.id(), turn.id(), new TeamItem(
                "it_team_visible", "team", "team_visible", "可见团队", "completed", "done",
                true, true, null, 1, 5, List.of()));
        recorder.recordItemAdded(thread.id(), turn.id(), new TeamItem(
                "it_team_hidden", "team", "team_hidden", "已移除团队", "failed", "removed by user",
                true, true, null, 1, 5, List.of()));
        applicationService.removeRuntimeItem("it_team_hidden", "team");

        List<TeamRecord> teams = repository.listByThreadId(thread.id());

        assertThat(teams).extracting(TeamRecord::teamId)
                .contains("team_visible")
                .doesNotContain("team_hidden");
    }

    @Test
    void list_by_thread_should_skip_team_when_team_record_was_removed() {
        Thread thread = conversationService.createThread("H:\\aaa");
        Turn turn = conversationService.startTurn(thread.id(), "run teams", "deepseek", "deepseek-v4-pro",
                "H:\\aaa", "DANGER_FULL_ACCESS", "ON_REQUEST");
        repository.save(new TeamRecord(
                        "team_record_hidden",
                        thread.id(),
                        turn.id(),
                        "Hidden team record",
                        "Should not reappear after restart",
                        "failed",
                        "H:\\aaa",
                        "WORKSPACE_WRITE",
                        true,
                        true,
                        5,
                        1,
                        null,
                        null,
                        "removed by user"),
                List.of());

        repository.markRemoved("team_record_hidden", Instant.parse("2026-06-17T00:00:00Z"));

        assertThat(repository.listByThreadId(thread.id()))
                .extracting(TeamRecord::teamId)
                .doesNotContain("team_record_hidden");
    }
}
