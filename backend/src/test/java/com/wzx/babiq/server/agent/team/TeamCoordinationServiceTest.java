package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.context.ApproximateContextTokenEstimator;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 团队协作 StateGraph 装配测试。
 *
 * <p>该测试不用真实模型，而是用假的 ReactAgent 节点验证 P6-3 的关键集成点：
 * supervisor graph 能运行，所有成员工厂收到同一个 shared saver/compileConfig，
 * 路由消息写入团队时间线。</p>
 */
class TeamCoordinationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void run_should_build_supervisor_graph_with_shared_saver_and_record_route_messages() {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_graph", "turn_graph"));
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) -> round == 0
                ? new SupervisorRouteDecision("explorer", "先读取目录", 0.9d)
                : SupervisorRouteDecision.finish("已有足够信息");
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(1, 18));
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_graph",
                "团队图",
                "查看项目结构",
                List.of(member("explorer", 1), member("reviewer", 2)),
                4,
                true,
                true,
                SandboxMode.READ_ONLY);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(memberFactory.savers).hasSize(2);
        assertThat(memberFactory.savers.get(0)).isSameAs(memberFactory.savers.get(1));
        assertThat(memberFactory.compileConfigs.get(0)).isSameAs(memberFactory.compileConfigs.get(1));
        assertThat(repository.listMessages("team_graph"))
                .extracting(TeamMessageRecord::messageType)
                .contains("route");
    }

    @Test
    void run_should_capture_member_output_as_summary_card_markdown_and_member_observation() throws Exception {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_capture", "turn_capture"));
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) -> round == 0
                ? new SupervisorRouteDecision("writer", "需要写入结果", 0.9d)
                : SupervisorRouteDecision.finish("writer 已完成");
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(2, 42));
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_capture",
                "团队捕获",
                "验证成员输出捕获",
                List.of(member("writer", 1)),
                4,
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(repository.listMessages("team_capture"))
                .filteredOn(message -> message.messageType().equals("member_summary"))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.fromAgent()).isEqualTo("writer");
                    assertThat(message.toAgent()).isEqualTo("supervisor");
                    assertThat(message.content()).contains("writer done").contains("详情见 rounds/r1-writer.md");
                    assertThat(message.turnId()).isEqualTo("turn_capture");
                });
        assertThat(repository.artifacts)
                .filteredOn(artifact -> artifact.artifactType().equals("MEMBER_OUTPUT"))
                .singleElement()
                .satisfies(artifact -> {
                    assertThat(artifact.relativePath()).isEqualTo("rounds/r1-writer.md");
                    assertThat(artifact.content()).isEqualTo("writer done");
                });
        assertThat(Files.readString(tempDir.resolve("team_capture").resolve("rounds").resolve("r1-writer.md")))
                .isEqualTo("writer done");
        assertThat(repository.memberUpdates)
                .singleElement()
                .satisfies(update -> {
                    assertThat(update.memberId()).isEqualTo("member_writer");
                    assertThat(update.status()).isEqualTo("completed");
                    assertThat(update.toolCallCount()).isEqualTo(2);
                    assertThat(update.tokenEstimate()).isEqualTo(42);
                    assertThat(update.summary()).contains("writer done").contains("详情见 rounds/r1-writer.md");
                });
    }

    @Test
    void run_should_pass_member_summary_timeline_to_supervisor_with_budget_truncation() {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_timeline", "turn_timeline"));
        repository.saveMessage(message("team_timeline", "route_old", "supervisor", "writer", "route", "先让 writer 执行", 1));
        repository.saveMessage(message("team_timeline", "summary_old", "writer", "supervisor",
                "member_summary", "旧成员摘要 ".repeat(200), 1));
        repository.saveMessage(message("team_timeline", "summary_recent", "reviewer", "supervisor",
                "member_summary", "最近复核摘要", 2));
        repository.saveMessage(message("team_timeline", "direct_recent", "user", "reviewer",
                "direct_user", "请重点检查路径", 2));
        AtomicReference<List<TeamMessageRecord>> seenTimeline = new AtomicReference<>();
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) -> {
            seenTimeline.set(timeline);
            return SupervisorRouteDecision.finish("已有足够信息");
        };
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(0, 0), 80);
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_timeline",
                "团队时间线",
                "验证 supervisor 时间线",
                List.of(member("writer", 1), member("reviewer", 2)),
                4,
                true,
                true,
                SandboxMode.READ_ONLY);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(seenTimeline.get()).isNotNull();
        assertThat(seenTimeline.get()).extracting(TeamMessageRecord::messageId)
                .contains("route_old", "summary_recent", "direct_recent")
                .doesNotContain("summary_old");
    }

    private BabiqTeamMember member(String name, int order) {
        return new BabiqTeamMember(
                "member_" + name,
                name,
                name,
                name,
                "执行 " + name,
                List.of("read_file"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                order,
                name + "_output",
                List.of());
    }

    private TeamCoordinationService service(TeamMemberAgentFactory memberFactory,
                                            SupervisorRoutingStrategy routingStrategy,
                                            InMemoryTeamRepository repository,
                                            TeamMemberObservationReader observationReader) {
        return service(memberFactory, routingStrategy, repository, observationReader, 3000);
    }

    private TeamCoordinationService service(TeamMemberAgentFactory memberFactory,
                                            SupervisorRoutingStrategy routingStrategy,
                                            InMemoryTeamRepository repository,
                                            TeamMemberObservationReader observationReader,
                                            int supervisorBudgetTokens) {
        TeamMemoryProperties properties = new TeamMemoryProperties(true, tempDir, supervisorBudgetTokens, 2000, 600, 12);
        TeamMemoryWorkspace workspace = new TeamMemoryWorkspace(
                properties,
                new ApproximateContextTokenEstimator(),
                repository);
        return new TeamCoordinationService(
                memberFactory,
                routingStrategy,
                repository,
                new ObjectMapper(),
                workspace,
                new TeamSummaryCardBuilder(new ApproximateContextTokenEstimator()),
                observationReader,
                new TeamDiscussionDigest(request -> new com.wzx.babiq.server.context.compaction.ContextCompactionStrategyResult(
                        "压缩后的团队概要"),
                        new ApproximateContextTokenEstimator()),
                properties,
                new ApproximateContextTokenEstimator());
    }

    private TeamMessageRecord message(String teamId,
                                      String messageId,
                                      String from,
                                      String to,
                                      String type,
                                      String content,
                                      int round) {
        return new TeamMessageRecord(teamId, messageId, "thread_" + teamId, "turn_" + teamId,
                from, to, type, content, null, round);
    }

    private TeamRecord teamRecord(String teamId, String turnId) {
        return new TeamRecord(
                teamId,
                "thread_" + teamId,
                turnId,
                teamId,
                "goal",
                "running",
                "H:\\aaa",
                SandboxMode.WORKSPACE_WRITE.name(),
                true,
                true,
                4,
                0,
                null,
                null,
                null);
    }

    private static class CapturingTeamMemberAgentFactory implements TeamMemberAgentFactory {
        private final List<BaseCheckpointSaver> savers = new ArrayList<>();
        private final List<CompileConfig> compileConfigs = new ArrayList<>();

        @Override
        public ReactAgent create(BabiqTeamMember member, String teamGoal, ToolContext toolContext,
                                 BaseCheckpointSaver sharedSaver, CompileConfig compileConfig) {
            savers.add(sharedSaver);
            compileConfigs.add(compileConfig);
            ReactAgent agent = mock(ReactAgent.class);
            when(agent.asNode(true, false)).thenReturn(new Node(member.name(), config ->
                    (state, runnableConfig) -> CompletableFuture.completedFuture(
                            Map.of(member.outputKey(), member.name() + " done"))));
            return agent;
        }
    }

    private static class InMemoryTeamRepository implements TeamRepository {
        private final List<TeamMessageRecord> messages = new ArrayList<>();
        private final List<TeamArtifactRecord> artifacts = new ArrayList<>();
        private final List<MemberUpdate> memberUpdates = new ArrayList<>();
        private final TeamRecord record;

        private InMemoryTeamRepository(TeamRecord record) {
            this.record = record;
        }

        @Override
        public void save(TeamRecord record, List<TeamMemberRecord> members) {
        }

        @Override
        public void updateMember(String teamId, String memberId, String status,
                                 int toolCallCount, int tokenEstimate, String summary) {
            memberUpdates.add(new MemberUpdate(teamId, memberId, status, toolCallCount, tokenEstimate, summary));
        }

        @Override
        public void saveMessage(TeamMessageRecord message) {
            messages.add(message);
        }

        @Override
        public void saveArtifact(TeamArtifactRecord artifact) {
            artifacts.add(artifact);
        }

        @Override
        public Optional<TeamRecord> findByTeamId(String teamId) {
            return record == null || !record.teamId().equals(teamId) ? Optional.empty() : Optional.of(record);
        }

        @Override
        public List<TeamMemberRecord> listMembers(String teamId) {
            return List.of();
        }

        @Override
        public List<TeamMessageRecord> listMessages(String teamId) {
            return messages.stream()
                    .filter(message -> message.teamId().equals(teamId))
                    .toList();
        }

        @Override
        public List<TeamArtifactRecord> listArtifacts(String teamId) {
            return artifacts.stream()
                    .filter(artifact -> artifact.teamId().equals(teamId))
                    .toList();
        }
    }

    private record MemberUpdate(String teamId, String memberId, String status,
                                int toolCallCount, int tokenEstimate, String summary) {
    }

    private record FixedTeamMemberObservationReader(int toolCalls, int tokens) implements TeamMemberObservationReader {

        @Override
        public TeamMemberObservation read(String turnId, String memberName, String fullText) {
            return new TeamMemberObservation(toolCalls, tokens);
        }
    }
}
