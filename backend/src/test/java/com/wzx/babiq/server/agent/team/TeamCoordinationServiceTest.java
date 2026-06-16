package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.internal.node.Node;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void run_should_build_supervisor_graph_with_shared_saver_and_record_route_messages() {
        CapturingTeamMemberAgentFactory memberFactory = new CapturingTeamMemberAgentFactory();
        InMemoryTeamRepository repository = new InMemoryTeamRepository();
        SupervisorRoutingStrategy routingStrategy = (spec, timeline, round) -> round == 0
                ? new SupervisorRouteDecision("explorer", "先读取目录", 0.9d)
                : SupervisorRouteDecision.finish("已有足够信息");
        TeamCoordinationService service = new TeamCoordinationService(
                memberFactory,
                routingStrategy,
                repository,
                new ObjectMapper());
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

        @Override
        public void save(TeamRecord record, List<TeamMemberRecord> members) {
        }

        @Override
        public void updateMember(String teamId, String memberId, String status,
                                 int toolCallCount, int tokenEstimate, String summary) {
        }

        @Override
        public void saveMessage(TeamMessageRecord message) {
            messages.add(message);
        }

        @Override
        public void saveArtifact(TeamArtifactRecord artifact) {
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
            return messages.stream()
                    .filter(message -> message.teamId().equals(teamId))
                    .toList();
        }

        @Override
        public List<TeamArtifactRecord> listArtifacts(String teamId) {
            return List.of();
        }
    }
}
