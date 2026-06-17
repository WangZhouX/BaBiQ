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
 * 团队协作逐轮调度测试。
 *
 * <p>该测试不用真实模型，而是用假的成员调用器验证 P6-3a 的关键集成点：
 * supervisor 每轮决策、直发消息在轮边界注入、成员产出被捕获，且旧有
 * 白名单和 maxRounds 保护不退化。</p>
 */
class TeamCoordinationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void run_should_execute_self_driven_loop_and_record_route_messages() {
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
        assertThat(memberFactory.savers).hasSize(1);
        assertThat(memberFactory.compileConfigs).containsExactly((CompileConfig) null);
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

    @Test
    void run_should_return_aggregated_summary_from_member_outputs_and_write_result_markdown() throws Exception {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_result", "turn_result"));
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) -> round == 0
                ? new SupervisorRouteDecision("writer", "先让 writer 产出结果", 0.9d)
                : SupervisorRouteDecision.finish("writer 已完成");
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(1, 16));
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_result",
                "团队结果",
                "验证团队结果聚合",
                List.of(member("writer", 1)),
                4,
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.summary())
                .contains("writer done")
                .contains("result.md")
                .isNotEqualTo("团队协作已完成");
        assertThat(repository.artifacts)
                .filteredOn(artifact -> artifact.artifactType().equals("RESULT"))
                .singleElement()
                .satisfies(artifact -> assertThat(artifact.content()).contains("writer done"));
        assertThat(Files.readString(tempDir.resolve("team_result").resolve("result.md")))
                .contains("writer done");
    }

    @Test
    void run_should_inject_direct_user_message_into_member_instruction_once() {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_inject", "turn_inject"));
        repository.saveMessage(message("team_inject", "direct_1", "user", "writer",
                "direct_user", "请把 index.html 改成成功页面。", 0));
        RecordingTeamMemberInvoker invoker = new RecordingTeamMemberInvoker();
        TeamDirectMessageService directMessageService = new TeamDirectMessageService(repository);
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) -> round == 0
                ? new SupervisorRouteDecision("writer", "执行用户补充指令", 0.9d)
                : SupervisorRouteDecision.finish("writer 已处理");
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(1, 12), 3000, invoker, directMessageService);
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_inject",
                "团队注入",
                "验证轮次间注入",
                List.of(member("writer", 1)),
                4,
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(invoker.inputs)
                .singleElement()
                .asString()
                .contains("请把 index.html 改成成功页面。");
        assertThat(directMessageService.drainForMember("team_inject", "writer")).isEmpty();
    }

    @Test
    void run_should_finish_when_supervisor_routes_to_unknown_member() {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_whitelist", "turn_whitelist"));
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) ->
                new SupervisorRouteDecision("intruder", "不在审批成员内", 0.9d);
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(0, 0));
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_whitelist",
                "团队白名单",
                "验证未知成员不会执行",
                List.of(member("writer", 1)),
                4,
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(memberFactory.savers).isEmpty();
        assertThat(repository.listMessages("team_whitelist"))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.toAgent()).isEqualTo("FINISH");
                    assertThat(message.content()).contains("白名单");
                });
    }

    @Test
    void run_should_stop_member_invocation_at_max_rounds() {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository(teamRecord("team_max", "turn_max"));
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) ->
                new SupervisorRouteDecision("writer", "继续写入", 0.9d);
        TeamCoordinationService service = service(memberFactory, routingStrategy, repository,
                new FixedTeamMemberObservationReader(0, 0));
        BabiqTeamSpec spec = new BabiqTeamSpec(
                "team_max",
                "团队轮次",
                "验证 maxRounds",
                List.of(member("writer", 1)),
                1,
                true,
                true,
                SandboxMode.WORKSPACE_WRITE);

        TeamExecutionResult result = service.run(spec, new ToolContext(Map.of()));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(memberFactory.savers).hasSize(1);
        assertThat(repository.listMessages("team_max"))
                .filteredOn(message -> "route".equals(message.messageType()))
                .extracting(TeamMessageRecord::toAgent)
                .containsExactly("writer", "FINISH");
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
        return service(memberFactory, routingStrategy, repository, observationReader, supervisorBudgetTokens,
                new RecordingTeamMemberInvoker());
    }

    private TeamCoordinationService service(TeamMemberAgentFactory memberFactory,
                                            SupervisorRoutingStrategy routingStrategy,
                                            InMemoryTeamRepository repository,
                                            TeamMemberObservationReader observationReader,
                                            int supervisorBudgetTokens,
                                            TeamMemberInvoker invoker) {
        return service(memberFactory, routingStrategy, repository, observationReader, supervisorBudgetTokens,
                invoker, new TeamDirectMessageService(repository));
    }

    private TeamCoordinationService service(TeamMemberAgentFactory memberFactory,
                                            SupervisorRoutingStrategy routingStrategy,
                                            InMemoryTeamRepository repository,
                                            TeamMemberObservationReader observationReader,
                                            int supervisorBudgetTokens,
                                            TeamMemberInvoker invoker,
                                            TeamDirectMessageService directMessageService) {
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
                new ApproximateContextTokenEstimator(),
                directMessageService,
                new TeamMemberContextAssembler(workspace),
                invoker);
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

    private static final class RecordingTeamMemberInvoker implements TeamMemberInvoker {
        private final List<String> inputs = new ArrayList<>();

        @Override
        public String invoke(com.alibaba.cloud.ai.graph.agent.ReactAgent agent, String input, ToolContext toolContext) {
            inputs.add(input);
            if (input.contains("writer")) {
                return "writer done";
            }
            if (input.contains("reviewer")) {
                return "reviewer done";
            }
            if (input.contains("explorer")) {
                return "explorer done";
            }
            return "member done";
        }
    }
}
