package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.context.ContextTokenEstimator;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P6-3 团队协作运行服务。
 *
 * <p>P6-3a 后团队不再把整轮协作塞进一次性 StateGraph invoke，而是由 BaBiQ 在
 * Java 层逐轮推进 supervisor 决策。每个成员执行仍薄封装官方 {@link ReactAgent}
 * 与 {@link com.alibaba.cloud.ai.graph.agent.AgentTool}，以保留 Spring AI Alibaba
 * Agent、工具、沙箱和观测链路。</p>
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
    /** 团队面板直发消息消费服务。 */
    private final TeamDirectMessageService directMessageService;
    /** 成员单轮上下文装配器。 */
    private final TeamMemberContextAssembler memberContextAssembler;
    /** 成员调用器，生产环境薄封装官方 AgentTool。 */
    private final TeamMemberInvoker memberInvoker;

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
                                   ContextTokenEstimator tokenEstimator,
                                   TeamDirectMessageService directMessageService,
                                   TeamMemberContextAssembler memberContextAssembler,
                                   TeamMemberInvoker memberInvoker) {
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
        this.directMessageService = directMessageService;
        this.memberContextAssembler = memberContextAssembler;
        this.memberInvoker = memberInvoker;
    }

    /**
     * 按 supervisor 决策逐轮推进团队协作。
     */
    public TeamExecutionResult run(BabiqTeamSpec spec, ToolContext parentToolContext) {
        if (!spec.approved() || !spec.frozen()) {
            throw new IllegalStateException("团队必须先完成运行前整体审批并冻结");
        }
        try {
            memoryWorkspace.initTeam(spec.teamId(), spec);
            ToolContext safeContext = safeContext(parentToolContext);
            int round = 0;
            String currentAgent = null;
            while (round <= spec.maxRounds()) {
                SupervisorRouteDecision rawDecision = decide(spec, round);
                SupervisorRouteDecision decision = normalize(spec, rawDecision, round);
                int nextRound = round + 1;
                repository.saveMessage(routeMessage(spec, decision, nextRound));
                if ("FINISH".equals(decision.next())) {
                    return new TeamExecutionResult("completed", aggregateResult(spec), nextRound, "FINISH");
                }
                BabiqTeamMember member = spec.member(decision.next())
                        .orElseThrow(() -> new IllegalStateException("路由成员不在团队中: " + decision.next()));
                currentAgent = member.name();
                ToolContext childContext = memberToolContext(safeContext, spec, member, nextRound);
                String instruction = memberContextAssembler.assembleMemberInstruction(
                        spec,
                        member,
                        nextRound,
                        decision.reason(),
                        directMessageService.drainForMember(spec.teamId(), member.name()));
                ReactAgent agent = memberAgentFactory.create(
                        member,
                        spec.goal(),
                        childContext,
                        new MemorySaver(),
                        null);
                String fullText = memberInvoker.invoke(agent, instruction, childContext);
                captureMemberOutput(spec, member, fullText, nextRound);
                round = nextRound;
            }
            return new TeamExecutionResult("completed", aggregateResult(spec), round, currentAgent);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new TeamExecutionResult("failed", message, 0, null);
        }
    }

    private void captureMemberOutput(BabiqTeamSpec spec, BabiqTeamMember member, String fullText, int round) {
        if (fullText == null || fullText.isBlank()) {
            return;
        }
        TeamRecord record = repository.findByTeamId(spec.teamId()).orElse(null);
        TeamArtifactRecord artifact = memoryWorkspace.writeMemberOutput(
                spec.teamId(),
                round,
                member.name(),
                fullText);
        String card = summaryCardBuilder.buildCard(
                member.name(),
                round,
                fullText,
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
                fullText);
        repository.updateMember(
                spec.teamId(),
                member.memberId(),
                "completed",
                observation.toolCallCount(),
                observation.tokenEstimate(),
                card);
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

    private String aggregateResult(BabiqTeamSpec spec) {
        List<TeamMessageRecord> summaries = repository.listMessages(spec.teamId()).stream()
                .filter(message -> "member_summary".equals(message.messageType()))
                .toList();
        String body;
        if (summaries.isEmpty()) {
            body = "暂无成员产出摘要。";
        } else {
            body = summaries.stream()
                    .map(message -> "### " + message.fromAgent() + " / round " + message.round() + "\n"
                            + message.content())
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
        }
        String markdown = """
                # 团队结果：%s

                目标：%s

                ## 成员摘要

                %s
                """.formatted(spec.title(), spec.goal(), body).trim();
        TeamArtifactRecord result = memoryWorkspace.writeResult(spec.teamId(), markdown);
        return "团队结果已聚合，详情见 " + result.relativePath() + "\n\n" + body;
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

    private ToolContext safeContext(ToolContext parentToolContext) {
        return parentToolContext == null ? new ToolContext(Map.of()) : parentToolContext;
    }

    private ToolContext memberToolContext(ToolContext parentToolContext,
                                          BabiqTeamSpec spec,
                                          BabiqTeamMember member,
                                          int round) {
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_team_" + spec.teamId() + "_" + round + "_" + member.memberId(),
                "dlg_team_" + spec.teamId() + "_" + round + "_" + member.memberId(),
                BuiltInSubAgents.MAIN_AGENT_NAME,
                member.name(),
                member.mode(),
                null,
                readObservation(parentToolContext));
        return SubAgentRuntimeFactory.withDelegationContext(parentToolContext, delegation, spec.sandboxMode());
    }

    private TurnObservationContext readObservation(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return value instanceof TurnObservationContext context ? context : null;
    }

}
