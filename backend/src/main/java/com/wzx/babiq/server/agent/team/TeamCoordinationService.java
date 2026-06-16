package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P6-3 团队协作运行服务。
 *
 * <p>该服务不自研流程引擎，而是把 BaBiQ 的团队规格翻译成 Spring AI Alibaba
 * {@link StateGraph}：一个 supervisor 节点负责路由，多个 teammate ReactAgent
 * 通过 {@code asNode(true,false)} 加入图。supervisor 和成员共享同一个
 * {@link MemorySaver} 与 {@link CompileConfig}。</p>
 */
@Service
public class TeamCoordinationService {

    /** 团队成员 ReactAgent 工厂。 */
    private final TeamMemberAgentFactory memberAgentFactory;
    /** supervisor 路由策略。 */
    private final SupervisorRoutingStrategy routingStrategy;
    /** 团队持久化端口。 */
    private final TeamRepository repository;
    /** JSON 序列化器，用于审计路由决策。 */
    private final ObjectMapper objectMapper;
    /** 团队记忆工作区，负责保存成员完整输出。 */
    private final TeamMemoryWorkspace memoryWorkspace;
    /** 成员摘要卡构建器。 */
    private final TeamSummaryCardBuilder summaryCardBuilder;
    /** 成员观测读取器，复用工具调用归属记录。 */
    private final TeamMemberObservationReader observationReader;
    /** 团队滚动讨论概要。 */
    private final TeamDiscussionDigest discussionDigest;
    /** 团队记忆配置。 */
    private final TeamMemoryProperties memoryProperties;
    /** token 粗估器，用于裁剪 supervisor 摘要时间线。 */
    private final ContextTokenEstimator tokenEstimator;

    /**
     * 创建团队协作服务。
     */
    public TeamCoordinationService(TeamMemberAgentFactory memberAgentFactory,
                                   SupervisorRoutingStrategy routingStrategy,
                                   TeamRepository repository,
                                   ObjectMapper objectMapper,
                                   TeamMemoryWorkspace memoryWorkspace,
                                   TeamSummaryCardBuilder summaryCardBuilder,
                                   TeamMemberObservationReader observationReader,
                                   TeamDiscussionDigest discussionDigest,
                                   TeamMemoryProperties memoryProperties,
                                   ContextTokenEstimator tokenEstimator) {
        this.memberAgentFactory = memberAgentFactory;
        this.routingStrategy = routingStrategy;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.memoryWorkspace = memoryWorkspace;
        this.summaryCardBuilder = summaryCardBuilder;
        this.observationReader = observationReader;
        this.discussionDigest = discussionDigest;
        this.memoryProperties = memoryProperties;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 构建并运行 supervisor/team 官方图。
     */
    public TeamExecutionResult run(BabiqTeamSpec spec, ToolContext parentToolContext) {
        if (!spec.approved() || !spec.frozen()) {
            throw new IllegalStateException("团队必须先完成运行前整体审批并冻结");
        }
        try {
            memoryWorkspace.initTeam(spec.teamId(), spec);
            BaseCheckpointSaver saver = new MemorySaver();
            CompileConfig compileConfig = CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(saver).build())
                    .build();
            StateGraph graph = buildGraph(spec, safeContext(parentToolContext), saver, compileConfig);
            CompiledGraph compiledGraph = graph.compile(compileConfig);
            OverAllState finalState = compiledGraph.invoke(Map.of("input", spec.goal())).orElse(null);
            int round = readRound(finalState);
            captureMemberOutputs(spec, finalState, round);
            String currentAgent = finalState == null ? null : String.valueOf(finalState.value("next", "FINISH"));
            return new TeamExecutionResult("completed", "团队协作已完成", round, currentAgent);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new TeamExecutionResult("failed", message, 0, null);
        }
    }

    private void captureMemberOutputs(BabiqTeamSpec spec, OverAllState finalState, int finalRound) {
        if (finalState == null) {
            return;
        }
        TeamRecord record = repository.findByTeamId(spec.teamId()).orElse(null);
        for (BabiqTeamMember member : spec.members()) {
            Optional<String> fullText = readOutput(finalState, member.outputKey());
            if (fullText.isEmpty()) {
                continue;
            }
            int round = outputRound(spec, member, finalRound);
            TeamArtifactRecord artifact = memoryWorkspace.writeMemberOutput(
                    spec.teamId(),
                    round,
                    member.name(),
                    fullText.get());
            String card = summaryCardBuilder.buildCard(
                    member.name(),
                    round,
                    fullText.get(),
                    Path.of(artifact.relativePath()),
                    memoryProperties.memberSummaryMaxChars());
            memoryWorkspace.appendIndexEntry(
                    spec.teamId(),
                    round,
                    member.name(),
                    oneLine(card),
                    Path.of(artifact.relativePath()));
            repository.saveMessage(memberSummaryMessage(spec, member, record, round, card));
            memoryWorkspace.writeDigest(spec.teamId(), discussionDigest.roll(
                    memoryWorkspace.readDigest(spec.teamId()),
                    card,
                    memoryProperties.discussionDigestBudgetTokens()));
            TeamMemberObservation observation = observationReader.read(
                    record == null ? null : record.turnId(),
                    member.name(),
                    fullText.get());
            repository.updateMember(
                    spec.teamId(),
                    member.memberId(),
                    "completed",
                    observation.toolCallCount(),
                    observation.tokenEstimate(),
                    card);
        }
    }

    private Optional<String> readOutput(OverAllState state, String outputKey) {
        Object value = state.value(outputKey, null);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Optional<?> optional) {
            value = optional.orElse(null);
            if (value == null) {
                return Optional.empty();
            }
        }
        String text = String.valueOf(value);
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private int outputRound(BabiqTeamSpec spec, BabiqTeamMember member, int finalRound) {
        return repository.listMessages(spec.teamId()).stream()
                .filter(message -> "route".equals(message.messageType()))
                .filter(message -> member.name().equals(message.toAgent()))
                .mapToInt(TeamMessageRecord::round)
                .max()
                .orElse(Math.max(1, finalRound));
    }

    private TeamMessageRecord memberSummaryMessage(BabiqTeamSpec spec,
                                                   BabiqTeamMember member,
                                                   TeamRecord record,
                                                   int round,
                                                   String card) {
        return new TeamMessageRecord(
                spec.teamId(),
                "msg_" + spec.teamId() + "_" + round + "_" + member.name() + "_summary",
                record == null ? null : record.threadId(),
                record == null ? null : record.turnId(),
                member.name(),
                "supervisor",
                "member_summary",
                card,
                null,
                round);
    }

    private String oneLine(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "...";
    }

    /**
     * 暴露图构建点，供测试确认官方 StateGraph 路径和 shared saver 注入。
     */
    StateGraph buildGraph(BabiqTeamSpec spec,
                          ToolContext toolContext,
                          BaseCheckpointSaver saver,
                          CompileConfig compileConfig) throws Exception {
        StateGraph graph = new StateGraph(spec.teamId(), keyStrategies(spec));
        graph.addNode("supervisor", AsyncNodeAction.node_async(state -> supervisorStep(spec, state)));
        graph.addEdge(StateGraph.START, "supervisor");
        for (BabiqTeamMember member : spec.members()) {
            graph.addNode(member.name(), memberAgentFactory
                    .create(member, spec.goal(), toolContext, saver, compileConfig)
                    .asNode(true, false));
            graph.addEdge(member.name(), "supervisor");
        }
        Map<String, String> routes = new LinkedHashMap<>();
        for (BabiqTeamMember member : spec.members()) {
            routes.put(member.name(), member.name());
        }
        routes.put("FINISH", StateGraph.END);
        graph.addConditionalEdges("supervisor",
                AsyncEdgeAction.edge_async(state -> String.valueOf(state.value("next", "FINISH"))),
                routes);
        return graph;
    }

    private Map<String, Object> supervisorStep(BabiqTeamSpec spec, OverAllState state) {
        int round = readRound(state);
        SupervisorRouteDecision rawDecision = decide(spec, round);
        SupervisorRouteDecision decision = normalize(spec, rawDecision, round);
        int nextRound = round + 1;
        repository.saveMessage(routeMessage(spec, decision, nextRound));
        return Map.of(
                "next", decision.next(),
                "route_reason", decision.reason(),
                "team_round", nextRound);
    }

    private SupervisorRouteDecision decide(BabiqTeamSpec spec, int round) {
        try {
            SupervisorRouteDecision decision = routingStrategy.decide(spec, supervisorTimeline(spec), round);
            return decision == null ? fallback(spec, round) : decision;
        } catch (RuntimeException exception) {
            return fallback(spec, round);
        }
    }

    private List<TeamMessageRecord> supervisorTimeline(BabiqTeamSpec spec) {
        List<TeamMessageRecord> timeline = new ArrayList<>(repository.listMessages(spec.teamId()));
        int budget = memoryProperties.supervisorContextBudgetTokens();
        if (budget <= 0) {
            return timeline;
        }
        while (tokenEstimate(timeline) > budget) {
            int index = firstMemberSummaryIndex(timeline);
            if (index < 0) {
                break;
            }
            timeline.remove(index);
        }
        return List.copyOf(timeline);
    }

    private int tokenEstimate(List<TeamMessageRecord> timeline) {
        return timeline.stream()
                .mapToInt(message -> tokenEstimator.estimate(message.content()))
                .sum();
    }

    private int firstMemberSummaryIndex(List<TeamMessageRecord> timeline) {
        for (int index = 0; index < timeline.size(); index++) {
            if ("member_summary".equals(timeline.get(index).messageType())) {
                return index;
            }
        }
        return -1;
    }

    private SupervisorRouteDecision normalize(BabiqTeamSpec spec, SupervisorRouteDecision decision, int round) {
        if (round >= spec.maxRounds()) {
            return SupervisorRouteDecision.finish("达到团队最大调度轮数");
        }
        if ("FINISH".equalsIgnoreCase(decision.next())) {
            return SupervisorRouteDecision.finish(decision.reason());
        }
        return spec.member(decision.next())
                .map(member -> new SupervisorRouteDecision(member.name(), decision.reason(), decision.confidence()))
                .orElseGet(() -> SupervisorRouteDecision.finish("路由结果不在审批成员白名单内"));
    }

    private SupervisorRouteDecision fallback(BabiqTeamSpec spec, int round) {
        if (round >= spec.members().size()) {
            return SupervisorRouteDecision.finish("所有成员都已至少调度一次");
        }
        BabiqTeamMember member = spec.members().get(round);
        return new SupervisorRouteDecision(member.name(), "按成员顺序调度", 0.5d);
    }

    private TeamMessageRecord routeMessage(BabiqTeamSpec spec, SupervisorRouteDecision decision, int round) {
        return new TeamMessageRecord(
                spec.teamId(),
                "msg_" + spec.teamId() + "_" + round,
                null,
                null,
                "supervisor",
                decision.next(),
                "route",
                decision.reason(),
                routeJson(decision),
                round);
    }

    private String routeJson(SupervisorRouteDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (Exception exception) {
            return "{\"next\":\"" + decision.next() + "\"}";
        }
    }

    private com.alibaba.cloud.ai.graph.KeyStrategyFactory keyStrategies(BabiqTeamSpec spec) {
        var builder = KeyStrategy.builder()
                .defaultStrategy(KeyStrategy.REPLACE)
                .addStrategy("input", KeyStrategy.REPLACE)
                .addStrategy("next", KeyStrategy.REPLACE)
                .addStrategy("route_reason", KeyStrategy.REPLACE)
                .addStrategy("team_round", KeyStrategy.REPLACE);
        for (BabiqTeamMember member : spec.members()) {
            builder.addStrategy(member.outputKey(), KeyStrategy.REPLACE);
        }
        return builder.build();
    }

    private int readRound(OverAllState state) {
        if (state == null) {
            return 0;
        }
        Object value = state.value("team_round", 0);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ToolContext safeContext(ToolContext parentToolContext) {
        return parentToolContext == null ? new ToolContext(Map.of()) : parentToolContext;
    }

}
