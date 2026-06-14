package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.agent.team.BabiqTeamMember;
import com.wzx.babiq.server.agent.team.BabiqTeamSpec;
import com.wzx.babiq.server.agent.team.TeamApprovalService;
import com.wzx.babiq.server.agent.team.TeamCoordinationService;
import com.wzx.babiq.server.agent.team.TeamExecutionResult;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.TeamItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.workunit.WorkUnitContextKeys;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * P6-3 团队协作工具。
 *
 * <p>主 Agent 通过 {@code coordinate_team} 一次性提交团队规格。工具本身进入 HITL
 * 审批和能力装配链路；团队内部成员仍复用 BaBiQ 的子 Agent、沙箱、Spotlighting、
 * 工具观测和 SQLite 审计。</p>
 */
@Component
public class TeamCoordinationTool implements Tool {

    /** 协议 item 类型。 */
    private static final String TYPE = "team";
    /** 未从 WorkUnit 显式启动时的拒绝提示。 */
    private static final String MISSING_WORK_UNIT_START_CONTEXT = """
            无法直接启动团队协作：当前 turn 没有绑定 WorkUnit goalId。
            请先使用 work_unit_manage 创建或复用团队工作容器，在右侧详情页完成成员、模型、权限和写入范围配置后，再显式启动该 WorkUnit。
            """;

    /** 团队运行服务，负责构建官方 StateGraph。 */
    private final TeamCoordinationService coordinationService;
    /** 团队持久化端口。 */
    private final TeamRepository repository;
    /** 运行前审批范围服务。 */
    private final TeamApprovalService approvalService;
    /** WorkUnit goal 状态回写服务；为空时表示非 WorkUnit 启动路径。 */
    private final WorkUnitService workUnitService;

    /**
     * 创建团队协作工具。
     */
    public TeamCoordinationTool(TeamCoordinationService coordinationService,
                                TeamRepository repository,
                                TeamApprovalService approvalService) {
        this(coordinationService, repository, approvalService, null);
    }

    @Autowired
    public TeamCoordinationTool(TeamCoordinationService coordinationService,
                                TeamRepository repository,
                                TeamApprovalService approvalService,
                                WorkUnitService workUnitService) {
        this.coordinationService = coordinationService;
        this.repository = repository;
        this.approvalService = approvalService;
        this.workUnitService = workUnitService;
    }

    @Override
    public String name() {
        return "coordinate_team";
    }

    /**
     * 启动一次 supervisor/team 协作。
     *
     * @param goal 团队整体目标
     * @param members 成员列表；为空时创建一个只读 explorer 成员
     * @param maxRounds supervisor 最多调度轮数
     * @param toolContext Spring AI 工具上下文，携带 cwd、沙箱、emitter、turn 观测信息
     * @return 给父 Agent 的团队协作结果摘要
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "coordinate_team",
            description = """
                    Coordinate a supervisor-led multi-agent team using Spring AI Alibaba StateGraph and shared MemorySaver.
                    团队协作、主管调度、队友、team、supervisor、多 Agent 协作。Use this only when the user explicitly
                    asks for a team of agents or the task needs coordinated teammates; simple one-step tasks should not use it.
                    Only call this after an explicit WorkUnit start has bound a goalId. For natural language requests to
                    use a team, call work_unit_manage first and ask the user to configure/start the WorkUnit.
                    """)
    public String coordinateTeam(
            @ToolParam(description = "团队整体目标，必须说明完成标准") String goal,
            @ToolParam(description = "团队成员列表；每个成员包含 name/displayName/role/task/toolNames/mode/writeScopes", required = false)
            List<TeamMemberInput> members,
            @ToolParam(description = "supervisor 最多调度轮数，默认 4，最大 12", required = false) Integer maxRounds,
            ToolContext toolContext) {
        String workUnitGoalId = workUnitGoalId(toolContext);
        if (workUnitGoalId == null) {
            return MISSING_WORK_UNIT_START_CONTEXT.trim();
        }
        String workUnitKindError = requireWorkUnitKind(workUnitGoalId, TYPE);
        if (workUnitKindError != null) {
            return workUnitKindError;
        }
        TurnObservationContext observation = observation(toolContext);
        ItemEmitter emitter = emitter(toolContext);
        SandboxMode sandboxMode = sandboxMode(toolContext);
        String cwd = cwd(toolContext);
        BabiqTeamSpec spec = approvalService.approveOnce(toSpec(goal, members, maxRounds, sandboxMode), sandboxMode);
        if (cwd != null) {
            approvalService.validateWriteScopes(spec, Path.of(cwd));
        }
        repository.save(record(spec, observation, cwd, "running", "团队协作已审批并开始执行", null, 0, null),
                memberRecords(spec, "pending", 0, 0, null));
        markWorkUnitGoalRunning(workUnitGoalId, spec.teamId());
        emit(emitter, item(spec, "running", "团队协作已审批并开始执行", 0, null));
        TeamExecutionResult result = coordinationService.run(spec, toolContext);
        String status = "failed".equals(result.status()) ? "failed" : "completed";
        repository.save(record(spec, observation, cwd, status, result.summary(), null, result.round(), result.currentAgent()),
                memberRecords(spec, status, 0, 0, result.summary()));
        emit(emitter, item(spec, status, result.summary(), result.round(), result.currentAgent()));
        if ("failed".equals(status)) {
            markWorkUnitGoalFailed(workUnitGoalId, result.summary());
        } else {
            markWorkUnitGoalCompleted(workUnitGoalId, result.summary());
        }
        return result.summary();
    }

    /**
     * 模型传入的团队成员参数。
     */
    public record TeamMemberInput(
            @ToolParam(description = "成员 ASCII 技术名，例如 explorer 或 writer") String name,
            @ToolParam(description = "成员展示名", required = false) String displayName,
            @ToolParam(description = "成员角色，例如 explorer、writer、reviewer", required = false) String role,
            @ToolParam(description = "成员任务") String task,
            @ToolParam(description = "成员可见工具白名单") List<String> toolNames,
            @ToolParam(description = "成员模式：READ_ONLY_TOOL 或 WORKSPACE_TOOL", required = false) String mode,
            @ToolParam(description = "可选 provider id；为空继承父 Agent", required = false) String providerId,
            @ToolParam(description = "写入范围，WORKSPACE_TOOL 成员必须尽量声明", required = false) List<String> writeScopes
    ) {
    }

    private BabiqTeamSpec toSpec(String goal, List<TeamMemberInput> members, Integer maxRounds, SandboxMode sandboxMode) {
        String id = "team_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        List<BabiqTeamMember> teamMembers = members == null || members.isEmpty()
                ? List.of(defaultMember(goal))
                : IntStream.range(0, members.size()).mapToObj(index -> toMember(members.get(index), index + 1)).toList();
        return new BabiqTeamSpec(id, blankToDefault(goal, "团队协作"), blankToDefault(goal, "团队协作"),
                teamMembers, maxRounds == null ? 4 : maxRounds, false, false, sandboxMode);
    }

    private BabiqTeamMember defaultMember(String goal) {
        return new BabiqTeamMember(
                "member_explorer",
                "explorer",
                "探索成员",
                "explorer",
                blankToDefault(goal, "查看当前上下文"),
                List.of("read_file", "list_dir", "grep"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                1,
                "explorer_output",
                List.of());
    }

    private BabiqTeamMember toMember(TeamMemberInput input, int order) {
        String name = blankToDefault(input == null ? null : input.name(), "member_" + order);
        BabiqAgentMode mode = "WORKSPACE_TOOL".equalsIgnoreCase(input == null ? null : input.mode())
                ? BabiqAgentMode.WORKSPACE_TOOL
                : BabiqAgentMode.READ_ONLY_TOOL;
        return new BabiqTeamMember(
                "member_" + name,
                name,
                blankToDefault(input == null ? null : input.displayName(), name),
                blankToDefault(input == null ? null : input.role(), name),
                blankToDefault(input == null ? null : input.task(), ""),
                input == null || input.toolNames() == null ? List.of() : input.toolNames(),
                input == null || input.providerId() == null || input.providerId().isBlank()
                        ? BabiqAgentSpec.ModelPolicy.inherit()
                        : BabiqAgentSpec.ModelPolicy.provider(input.providerId()),
                mode,
                order,
                name + "_output",
                input == null || input.writeScopes() == null ? List.of() : input.writeScopes());
    }

    private TeamRecord record(BabiqTeamSpec spec,
                              TurnObservationContext observation,
                              String cwd,
                              String status,
                              String summary,
                              String error,
                              int round,
                              String currentAgent) {
        return new TeamRecord(
                spec.teamId(),
                observation == null ? null : observation.threadId(),
                observation == null ? null : observation.turnId(),
                spec.title(),
                spec.goal(),
                status,
                cwd,
                spec.sandboxMode().name(),
                spec.approved(),
                spec.frozen(),
                spec.maxRounds(),
                round,
                currentAgent,
                summary,
                error);
    }

    private List<TeamMemberRecord> memberRecords(BabiqTeamSpec spec, String status,
                                                 int toolCalls, int tokens, String summary) {
        return spec.members().stream()
                .map(member -> new TeamMemberRecord(
                        spec.teamId(),
                        member.memberId(),
                        member.name(),
                        member.displayName(),
                        member.role(),
                        member.mode().name(),
                        String.join(",", member.toolNames()),
                        status,
                        member.order(),
                        toolCalls,
                        tokens,
                        summary))
                .toList();
    }

    private TeamItem item(BabiqTeamSpec spec, String status, String summary, int round, String currentAgent) {
        return new TeamItem(
                "it_" + spec.teamId(),
                TYPE,
                spec.teamId(),
                spec.title(),
                status,
                summary,
                spec.approved(),
                spec.frozen(),
                currentAgent,
                round,
                spec.maxRounds(),
                spec.members().stream()
                        .map(member -> new TeamItem.MemberStatus(
                                member.memberId(),
                                member.name(),
                                member.displayName(),
                                status,
                                member.mode().name(),
                                member.task(),
                                0,
                                0,
                                summary))
                        .toList());
    }

    private void emit(ItemEmitter emitter, TeamItem item) {
        if (emitter == null) {
            return;
        }
        try {
            if ("running".equals(item.status())) {
                emitter.emitItemAdded(item);
            } else {
                emitter.emitItemUpdated(item);
            }
        } catch (IOException ignored) {
            // UI 事件失败不能反向中断团队协作；SQLite 记录仍保留审计事实。
        }
    }

    private void markWorkUnitGoalRunning(String goalId, String teamId) {
        if (workUnitService != null && goalId != null) {
            workUnitService.markGoalRunning(goalId, "team", teamId);
        }
    }

    private String requireWorkUnitKind(String goalId, String expectedKind) {
        if (workUnitService == null || goalId == null) {
            return null;
        }
        try {
            workUnitService.requireGoalKind(goalId, expectedKind);
            return null;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return exception.getMessage() == null ? "WorkUnit kind mismatch" : exception.getMessage();
        }
    }

    private void markWorkUnitGoalCompleted(String goalId, String summary) {
        if (workUnitService != null && goalId != null) {
            workUnitService.markGoalCompleted(goalId, summary);
        }
    }

    private void markWorkUnitGoalFailed(String goalId, String message) {
        if (workUnitService != null && goalId != null) {
            workUnitService.markGoalFailed(goalId, message);
        }
    }

    private TurnObservationContext observation(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return value instanceof TurnObservationContext observation ? observation : null;
    }

    private ItemEmitter emitter(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        return value instanceof ItemEmitter emitter ? emitter : null;
    }

    private SandboxMode sandboxMode(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE);
        if (value instanceof String text) {
            try {
                return SandboxMode.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                return SandboxMode.READ_ONLY;
            }
        }
        return SandboxMode.READ_ONLY;
    }

    private String cwd(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_CWD);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String workUnitGoalId(ToolContext toolContext) {
        String goalId = WorkUnitContextKeys.goalId(toolContext);
        return goalId == null || goalId.isBlank() ? null : goalId;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
