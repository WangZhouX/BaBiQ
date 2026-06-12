package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitContextKeys;
import com.wzx.babiq.server.workunit.WorkUnitCreateRequest;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工作容器自然语言管理工具。
 *
 * <p>该工具用于主 Agent 在用户明确表达时创建、复用、追加、启动或移除命名的编排/团队容器。
 * 它本身不运行 flow/team；start 动作只把目标 id 写进同一轮 ToolContext，随后真实执行仍必须由
 * {@code orchestrate_flow} 或 {@code coordinate_team} 工具显式完成。</p>
 */
@Component
public class WorkUnitManageTool implements Tool {

    private static final String ACTION_APPEND_GOAL = "append_goal";
    private static final String ACTION_UPDATE_GOAL = "update_goal";
    private static final String ACTION_START = "start";
    private static final String ACTION_REMOVE = "remove";
    private static final String STATUS_RUNNING = "running";
    private static final String GOAL_PENDING = "pending";
    private static final String KIND_ORCHESTRATION = "orchestration";
    private static final String KIND_TEAM = "team";

    private final WorkUnitService service;

    public WorkUnitManageTool(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return "work_unit_manage";
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "work_unit_manage",
            description = "管理 BaBiQ 命名工作容器：创建/复用编排或团队、追加目标、修改待配置目标、关联待启动目标、二次确认后移除已结束容器",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult manage(
            @ToolParam(description = "动作：append_goal/create/update_goal/start/remove，也可使用中文 创建/追加/修改目标/启动/移除")
            String action,
            @ToolParam(description = "容器类型：orchestration/flow/编排 或 team/团队", required = false)
            String kind,
            @ToolParam(description = "容器名称；同一对话内运行中的同名容器不能重复", required = false)
            String name,
            @ToolParam(description = "目标文本；append_goal/create/update_goal 必填", required = false)
            String goal,
            @ToolParam(description = "容器 id；remove 或 start 可直接指定", required = false)
            String workUnitId,
            @ToolParam(description = "仅 remove 动作使用。必须在用户明确二次确认后传 true；未确认时不要移除容器", required = false)
            Boolean confirmed,
            ToolContext toolContext) {
        RuntimeContext runtime = runtime(toolContext);
        if (runtime == null) {
            return ToolResult.failure("缺少 BaBiQ 运行上下文，无法管理工作容器");
        }
        return switch (normalizeAction(action)) {
            case ACTION_APPEND_GOAL -> appendGoal(kind, name, goal, runtime);
            case ACTION_UPDATE_GOAL -> updateGoal(kind, name, goal, workUnitId, runtime);
            case ACTION_START -> bindStartGoal(kind, name, workUnitId, runtime);
            case ACTION_REMOVE -> remove(kind, name, workUnitId, Boolean.TRUE.equals(confirmed), runtime);
            default -> ToolResult.failure("不支持的工作容器动作: " + action);
        };
    }

    private ToolResult appendGoal(String kind, String name, String goal, RuntimeContext runtime) {
        String normalizedKind = normalizeKind(kind);
        if (normalizedKind == null) {
            return ToolResult.failure("工作容器类型必须是 orchestration/编排 或 team/团队");
        }
        if (isBlank(name) || isBlank(goal)) {
            return ToolResult.failure("创建或追加工作容器目标时，name 和 goal 都不能为空");
        }
        WorkUnitItem item = service.createOrAppend(
                new WorkUnitCreateRequest(normalizedKind, name.trim(), goal.trim(), null),
                Thread.newThread(runtime.observation.threadId(), runtime.cwd),
                new Turn(runtime.observation.turnId(), runtime.observation.threadId()),
                runtime.cwd,
                AgentRunPolicy.of(runtime.sandboxMode, ApprovalPolicy.ON_REQUEST));
        emitAdded(runtime.emitter, item);
        return ToolResult.ok("已准备工作容器 " + item.workUnitId()
                + "，当前目标 " + item.activeGoalId()
                + "。这只是配置/排队，尚未启动真实执行；请在右侧详情页检查节点/成员、模型、工具权限、写入范围和沙箱策略后，再显式启动。");
    }

    private ToolResult bindStartGoal(String kind, String name, String workUnitId, RuntimeContext runtime) {
        WorkUnit workUnit = findWorkUnit(kind, name, workUnitId, runtime.observation.threadId())
                .orElse(null);
        if (workUnit == null) {
            return ToolResult.failure("找不到要启动的工作容器");
        }
        if (STATUS_RUNNING.equals(workUnit.status())) {
            return ToolResult.failure("工作容器正在运行，不能重复启动: " + workUnit.name());
        }
        WorkUnitGoal goal = selectStartGoal(workUnit).orElse(null);
        if (goal == null) {
            return ToolResult.failure("工作容器没有可启动的待处理目标: " + workUnit.name());
        }
        runtime.observation.rememberWorkUnitGoalId(goal.goalId());
        String suggestedTool = KIND_TEAM.equals(workUnit.kind()) ? "coordinate_team" : "orchestrate_flow";
        return ToolResult.ok("已关联工作容器目标 " + goal.goalId()
                + "。请继续调用 " + suggestedTool
                + " 执行该目标，执行工具会把运行记录写回该目标。");
    }

    private ToolResult updateGoal(String kind, String name, String goalText, String workUnitId, RuntimeContext runtime) {
        WorkUnit workUnit = findWorkUnit(kind, name, workUnitId, runtime.observation.threadId())
                .orElse(null);
        if (workUnit == null) {
            return ToolResult.failure("找不到要修改的工作容器");
        }
        if (isBlank(goalText)) {
            return ToolResult.failure("修改工作容器目标时，goal 不能为空");
        }
        WorkUnitGoal goal = selectStartGoal(workUnit).orElse(null);
        if (goal == null) {
            return ToolResult.failure("工作容器没有可修改的 pending 目标: " + workUnit.name());
        }
        WorkUnitGoal updatedGoal = service.updateGoal(goal.goalId(), goalText.trim());
        WorkUnitItem item = service.itemFor(workUnit);
        emitUpdated(runtime.emitter, item);
        return ToolResult.ok("已修改工作容器目标 " + updatedGoal.goalId()
                + "，新的目标为: " + updatedGoal.goalText());
    }

    private ToolResult remove(String kind, String name, String workUnitId, boolean confirmed, RuntimeContext runtime) {
        WorkUnit target = findWorkUnit(kind, name, workUnitId, runtime.observation.threadId())
                .orElse(null);
        if (target == null) {
            return ToolResult.failure("找不到要移除的工作容器");
        }
        if (!confirmed) {
            return ToolResult.failure("移除 WorkUnit 需要二次确认。目标: "
                    + target.kind() + " / " + target.name() + " / " + target.workUnitId()
                    + "。请先向用户说明将软移除此编排或团队，确认审计记录仍保留；只有用户明确确认后，才能再次调用 work_unit_manage remove 并传 confirmed=true。");
        }
        WorkUnit removed = service.remove(target.workUnitId());
        WorkUnitItem item = service.itemFor(removed);
        emitUpdated(runtime.emitter, item);
        return ToolResult.ok("已移除工作容器 " + removed.workUnitId() + "，审计记录仍保留。");
    }

    private Optional<WorkUnit> findWorkUnit(String kind, String name, String workUnitId, String threadId) {
        List<WorkUnit> visible = service.listVisible(threadId);
        if (!isBlank(workUnitId)) {
            return visible.stream()
                    .filter(unit -> workUnitId.trim().equals(unit.workUnitId()))
                    .findFirst();
        }
        String normalizedKind = normalizeKind(kind);
        String normalizedName = normalizeName(name);
        if (normalizedKind == null || normalizedName.isBlank()) {
            return Optional.empty();
        }
        return visible.stream()
                .filter(unit -> normalizedKind.equals(unit.kind()))
                .filter(unit -> normalizedName.equals(normalizeName(unit.name())))
                .findFirst();
    }

    private Optional<WorkUnitGoal> selectStartGoal(WorkUnit workUnit) {
        List<WorkUnitGoal> goals = service.listGoals(workUnit.workUnitId());
        return goals.stream()
                .filter(goal -> Objects.equals(goal.goalId(), workUnit.currentGoalId()))
                .filter(goal -> GOAL_PENDING.equals(goal.status()))
                .findFirst()
                .or(() -> goals.stream()
                        .filter(goal -> GOAL_PENDING.equals(goal.status()))
                        .max(Comparator.comparing(WorkUnitGoal::createdAt)));
    }

    private RuntimeContext runtime(ToolContext toolContext) {
        Map<String, Object> context = toolContext == null ? null : toolContext.getContext();
        if (context == null) {
            return null;
        }
        Object observationCandidate = context.get(TurnObservationContext.METADATA_KEY);
        if (!(observationCandidate instanceof TurnObservationContext observation)) {
            return null;
        }
        Object cwdCandidate = context.get(BaBiQSandboxInterceptor.CONTEXT_CWD);
        String cwd = cwdCandidate instanceof String value && !value.isBlank() ? value : ".";
        Object emitterCandidate = context.get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        ItemEmitter emitter = emitterCandidate instanceof ItemEmitter value ? value : null;
        Object sandboxCandidate = context.get(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE);
        SandboxMode sandboxMode = parseSandboxMode(sandboxCandidate);
        return new RuntimeContext(observation, cwd, emitter, sandboxMode);
    }

    private void emitAdded(ItemEmitter emitter, WorkUnitItem item) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emitItemAdded(item);
        } catch (IOException exception) {
            throw new IllegalStateException("推送工作容器 item 失败", exception);
        }
    }

    private void emitUpdated(ItemEmitter emitter, WorkUnitItem item) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.emitItemUpdated(item);
        } catch (IOException exception) {
            throw new IllegalStateException("更新工作容器 item 失败", exception);
        }
    }

    private static String normalizeAction(String action) {
        if (isBlank(action)) {
            return "";
        }
        return switch (action.trim().toLowerCase(Locale.ROOT)) {
            case "append", "append_goal", "create", "创建", "追加", "追加目标" -> ACTION_APPEND_GOAL;
            case "update", "update_goal", "modify", "修改", "更新", "修改目标", "更新目标" -> ACTION_UPDATE_GOAL;
            case "start", "启动", "开始" -> ACTION_START;
            case "remove", "delete", "移除", "删除" -> ACTION_REMOVE;
            default -> action.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String normalizeKind(String kind) {
        if (isBlank(kind)) {
            return null;
        }
        return switch (kind.trim().toLowerCase(Locale.ROOT)) {
            case "orchestration", "flow", "编排" -> KIND_ORCHESTRATION;
            case "team", "团队" -> KIND_TEAM;
            default -> null;
        };
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static SandboxMode parseSandboxMode(Object value) {
        if (value instanceof SandboxMode mode) {
            return mode;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return SandboxMode.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                return SandboxMode.WORKSPACE_WRITE;
            }
        }
        return SandboxMode.WORKSPACE_WRITE;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RuntimeContext(
            TurnObservationContext observation,
            String cwd,
            ItemEmitter emitter,
            SandboxMode sandboxMode
    ) {
    }
}
