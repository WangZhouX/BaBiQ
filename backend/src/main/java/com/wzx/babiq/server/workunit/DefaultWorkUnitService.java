package com.wzx.babiq.server.workunit;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 默认工作容器应用服务。
 *
 * <p>Slash 路径进入这里后只做确定性的容器创建/复用和目标追加，不启动 flow/team。
 * 这条边界很重要：执行必须来自用户后续在详情页点击开始，或明确要求主 Agent 启动。</p>
 */
@Service
public class DefaultWorkUnitService implements WorkUnitService {

    static final String STATUS_WAITING_CONFIG = "waiting_config";
    static final String STATUS_RUNNING = "running";
    static final String STATUS_COMPLETED = "completed";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_REMOVED = "removed";

    static final String GOAL_PENDING = "pending";
    static final String GOAL_RUNNING = "running";
    static final String GOAL_COMPLETED = "completed";

    private final WorkUnitRepository repository;

    public DefaultWorkUnitService(WorkUnitRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public synchronized WorkUnitItem createOrAppend(WorkUnitCreateRequest request,
                                                    Thread thread,
                                                    Turn turn,
                                                    String cwd,
                                                    AgentRunPolicy runPolicy) {
        validateRequest(request, thread);
        String kind = request.kind().trim();
        String normalizedName = normalizeName(request.name());
        Instant now = Instant.now();
        WorkUnit workUnit = repository.findVisibleByName(thread.id(), kind, normalizedName)
                .orElseGet(() -> createWorkUnit(request, thread, cwd, runPolicy, normalizedName, now));
        WorkUnitGoal goal = new WorkUnitGoal(
                request.goalId() == null || request.goalId().isBlank() ? newGoalId() : request.goalId().trim(),
                workUnit.workUnitId(),
                thread.id(),
                request.goal().trim(),
                GOAL_PENDING,
                null,
                null,
                null,
                null,
                now,
                null,
                null);
        repository.saveGoal(goal);

        WorkUnit updated = workUnit;
        if (!STATUS_RUNNING.equals(workUnit.status())) {
            updated = new WorkUnit(
                    workUnit.workUnitId(),
                    workUnit.threadId(),
                    workUnit.kind(),
                    workUnit.name(),
                    workUnit.normalizedName(),
                    STATUS_WAITING_CONFIG,
                    goal.goalId(),
                    workUnit.cwd(),
                    workUnit.sandboxMode(),
                    false,
                    null,
                    workUnit.createdAt(),
                    now);
            updated = repository.save(updated);
        }
        return itemFor(updated);
    }

    @Override
    public List<WorkUnit> listVisible(String threadId) {
        return repository.listVisible(threadId);
    }

    @Override
    public List<WorkUnitGoal> listGoals(String workUnitId) {
        return repository.listGoals(workUnitId);
    }

    @Override
    public WorkUnitItem itemFor(WorkUnit workUnit) {
        if (workUnit == null) {
            throw new IllegalArgumentException("工作容器不能为空");
        }
        List<WorkUnitGoal> goals = repository.listGoals(workUnit.workUnitId());
        WorkUnitGoal active = goals.stream()
                .filter(goal -> goal.goalId().equals(workUnit.currentGoalId()))
                .findFirst()
                .orElseGet(() -> goals.isEmpty() ? null : goals.getLast());
        return new WorkUnitItem(
                "it_workunit_" + workUnit.workUnitId().replaceFirst("^wu_", ""),
                "workUnit",
                workUnit.workUnitId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.status(),
                active == null ? null : active.goalId(),
                active == null ? null : active.goalText(),
                goals.size(),
                active == null ? null : active.runRefId());
    }

    @Override
    @Transactional
    public void markGoalRunning(String goalId, String runRefType, String runRefId) {
        WorkUnitGoal goal = repository.findGoalById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("工作目标不存在: " + goalId));
        WorkUnit workUnit = repository.findById(goal.workUnitId())
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + goal.workUnitId()));
        Instant now = Instant.now();
        repository.saveGoal(new WorkUnitGoal(
                goal.goalId(),
                goal.workUnitId(),
                goal.threadId(),
                goal.goalText(),
                GOAL_RUNNING,
                runRefType,
                runRefId,
                goal.summary(),
                goal.errorMessage(),
                goal.createdAt(),
                now,
                goal.completedAt()));
        repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.normalizedName(),
                STATUS_RUNNING,
                goal.goalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                workUnit.removedAt(),
                workUnit.createdAt(),
                now));
    }

    @Override
    @Transactional
    public WorkUnitGoal updateGoal(String goalId, String goalText) {
        if (goalId == null || goalId.isBlank()) {
            throw new IllegalArgumentException("工作目标 id 不能为空");
        }
        if (goalText == null || goalText.isBlank()) {
            throw new IllegalArgumentException("工作目标文本不能为空");
        }
        WorkUnitGoal goal = repository.findGoalById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("工作目标不存在: " + goalId));
        if (!GOAL_PENDING.equals(goal.status())) {
            throw new IllegalStateException("只有 pending 目标可以修改，当前状态: " + goal.status());
        }
        WorkUnit workUnit = repository.findById(goal.workUnitId())
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + goal.workUnitId()));
        Instant now = Instant.now();
        WorkUnitGoal updated = repository.saveGoal(new WorkUnitGoal(
                goal.goalId(),
                goal.workUnitId(),
                goal.threadId(),
                goalText.trim(),
                goal.status(),
                goal.runRefType(),
                goal.runRefId(),
                goal.summary(),
                goal.errorMessage(),
                goal.createdAt(),
                goal.startedAt(),
                goal.completedAt()));
        repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.normalizedName(),
                workUnit.status(),
                workUnit.currentGoalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                workUnit.removedAt(),
                workUnit.createdAt(),
                now));
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkUnitGoal selectPendingGoalForTurn(String threadId, String workUnitId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId 不能为空");
        }
        if (workUnitId == null || workUnitId.isBlank()) {
            throw new IllegalArgumentException("工作容器 id 不能为空");
        }
        WorkUnit workUnit = repository.findById(workUnitId)
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + workUnitId));
        if (!threadId.equals(workUnit.threadId())) {
            throw new IllegalArgumentException("工作容器不属于当前对话: " + threadId);
        }
        if (workUnit.removed() || STATUS_REMOVED.equals(workUnit.status())) {
            throw new IllegalStateException("已移除的工作容器不能启动");
        }
        if (STATUS_RUNNING.equals(workUnit.status())) {
            throw new IllegalStateException("运行中的工作容器不能重复启动");
        }
        return selectPendingGoal(workUnit);
    }

    @Override
    @Transactional
    public void markGoalCompleted(String goalId, String summary) {
        WorkUnitGoal goal = repository.findGoalById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("工作目标不存在: " + goalId));
        WorkUnit workUnit = repository.findById(goal.workUnitId())
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + goal.workUnitId()));
        Instant now = Instant.now();
        repository.saveGoal(new WorkUnitGoal(
                goal.goalId(),
                goal.workUnitId(),
                goal.threadId(),
                goal.goalText(),
                GOAL_COMPLETED,
                goal.runRefType(),
                goal.runRefId(),
                summary,
                goal.errorMessage(),
                goal.createdAt(),
                goal.startedAt(),
                now));
        repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.normalizedName(),
                STATUS_COMPLETED,
                goal.goalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                workUnit.removedAt(),
                workUnit.createdAt(),
                now));
    }

    @Override
    @Transactional
    public void markGoalFailed(String goalId, String errorMessage) {
        WorkUnitGoal goal = repository.findGoalById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("工作目标不存在: " + goalId));
        WorkUnit workUnit = repository.findById(goal.workUnitId())
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + goal.workUnitId()));
        Instant now = Instant.now();
        repository.saveGoal(new WorkUnitGoal(
                goal.goalId(),
                goal.workUnitId(),
                goal.threadId(),
                goal.goalText(),
                STATUS_FAILED,
                goal.runRefType(),
                goal.runRefId(),
                goal.summary(),
                errorMessage,
                goal.createdAt(),
                goal.startedAt(),
                now));
        repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.normalizedName(),
                STATUS_FAILED,
                goal.goalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                workUnit.removedAt(),
                workUnit.createdAt(),
                now));
    }

    @Override
    @Transactional
    public WorkUnit remove(String workUnitId) {
        WorkUnit workUnit = repository.findById(workUnitId)
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + workUnitId));
        if (STATUS_RUNNING.equals(workUnit.status())) {
            throw new IllegalStateException("运行中的工作容器不能直接移除");
        }
        Instant now = Instant.now();
        return repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.normalizedName(),
                STATUS_REMOVED,
                workUnit.currentGoalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                true,
                now,
                workUnit.createdAt(),
                now));
    }

    private WorkUnit createWorkUnit(WorkUnitCreateRequest request,
                                    Thread thread,
                                    String cwd,
                                    AgentRunPolicy runPolicy,
                                    String normalizedName,
                                    Instant now) {
        return repository.save(new WorkUnit(
                newWorkUnitId(),
                thread.id(),
                request.kind().trim(),
                request.name().trim(),
                normalizedName,
                STATUS_WAITING_CONFIG,
                null,
                cwd,
                runPolicy == null ? null : runPolicy.sandboxMode().name(),
                false,
                null,
                now,
                now));
    }

    private static void validateRequest(WorkUnitCreateRequest request, Thread thread) {
        if (thread == null || thread.id() == null || thread.id().isBlank()) {
            throw new IllegalArgumentException("thread 不能为空");
        }
        if (request == null) {
            throw new IllegalArgumentException("工作容器创建请求不能为空");
        }
        if (!"orchestration".equals(request.kind()) && !"team".equals(request.kind())) {
            throw new IllegalArgumentException("工作容器类型仅支持 orchestration 或 team");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("工作容器名称不能为空");
        }
        if (request.goal() == null || request.goal().isBlank()) {
            throw new IllegalArgumentException("工作目标不能为空");
        }
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private WorkUnitGoal selectPendingGoal(WorkUnit workUnit) {
        List<WorkUnitGoal> goals = repository.listGoals(workUnit.workUnitId());
        if (workUnit.currentGoalId() != null && !workUnit.currentGoalId().isBlank()) {
            for (WorkUnitGoal goal : goals) {
                if (workUnit.currentGoalId().equals(goal.goalId()) && GOAL_PENDING.equals(goal.status())) {
                    return goal;
                }
            }
        }
        for (int index = goals.size() - 1; index >= 0; index--) {
            WorkUnitGoal goal = goals.get(index);
            if (GOAL_PENDING.equals(goal.status())) {
                return goal;
            }
        }
        throw new IllegalStateException("工作容器没有可启动的 pending 目标: " + workUnit.workUnitId());
    }

    private static String newWorkUnitId() {
        return "wu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String newGoalId() {
        return "goal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
