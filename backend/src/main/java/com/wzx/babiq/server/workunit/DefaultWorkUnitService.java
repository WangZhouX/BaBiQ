package com.wzx.babiq.server.workunit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.agent.team.TeamMemberRecord;
import com.wzx.babiq.server.agent.team.TeamRecord;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    static final String GOAL_FAILED = "failed";

    private static final String ABANDONED_RUNNING_MESSAGE = "启动恢复：上一轮工作容器运行已中断";

    private static final List<String> DEFAULT_READ_TOOLS = List.of("read_file", "list_dir", "grep");
    private static final List<String> DEFAULT_WORKSPACE_TOOLS = List.of("read_file", "list_dir", "grep", "write_file", "apply_patch");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private final WorkUnitRepository repository;
    private final TeamRepository teamRepository;

    public DefaultWorkUnitService(WorkUnitRepository repository) {
        this(repository, null);
    }

    @Autowired
    public DefaultWorkUnitService(WorkUnitRepository repository, TeamRepository teamRepository) {
        this.repository = repository;
        this.teamRepository = teamRepository;
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
        syncTeamSpace(updated, goal);
        return itemFor(updated);
    }

    @Override
    public List<WorkUnit> listVisible(String threadId) {
        List<WorkUnit> workUnits = repository.listVisible(threadId);
        workUnits.forEach(this::syncTeamSpaceIfNeeded);
        return workUnits;
    }

    @Override
    public List<WorkUnitGoal> listGoals(String workUnitId) {
        return repository.listGoals(workUnitId);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireGoalKind(String goalId, String expectedKind) {
        if (goalId == null || goalId.isBlank()) {
            throw new IllegalArgumentException("WorkUnit goalId must not be blank");
        }
        if (expectedKind == null || expectedKind.isBlank()) {
            throw new IllegalArgumentException("WorkUnit expected kind must not be blank");
        }
        WorkUnitGoal goal = repository.findGoalById(goalId)
                .orElseThrow(() -> new IllegalArgumentException("WorkUnit goal does not exist: " + goalId));
        WorkUnit workUnit = repository.findById(goal.workUnitId())
                .orElseThrow(() -> new IllegalArgumentException("WorkUnit does not exist: " + goal.workUnitId()));
        if (!expectedKind.equals(workUnit.kind())) {
            throw new IllegalStateException("WorkUnit kind mismatch: expected " + expectedKind + " but was " + workUnit.kind());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> teamIdForGoal(String goalId) {
        if (goalId == null || goalId.isBlank()) {
            return Optional.empty();
        }
        return repository.findGoalById(goalId)
                .flatMap(goal -> repository.findById(goal.workUnitId()))
                .filter(workUnit -> "team".equals(workUnit.kind()))
                .map(workUnit -> teamIdForWorkUnit(workUnit.workUnitId()));
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
        syncTeamSpace(workUnit, updated);
        return updated;
    }

    @Override
    @Transactional
    public synchronized WorkUnit rename(String workUnitId, String name) {
        if (workUnitId == null || workUnitId.isBlank()) {
            throw new IllegalArgumentException("workUnitId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("WorkUnit name must not be blank");
        }
        WorkUnit workUnit = repository.findById(workUnitId)
                .orElseThrow(() -> new IllegalArgumentException("WorkUnit does not exist: " + workUnitId));
        if (workUnit.removed() || STATUS_REMOVED.equals(workUnit.status())) {
            throw new IllegalStateException("Removed WorkUnit cannot be renamed");
        }
        if (STATUS_RUNNING.equals(workUnit.status())) {
            throw new IllegalStateException("Running WorkUnit cannot be renamed");
        }
        String trimmedName = name.trim().replaceAll("\\s+", " ");
        String normalizedName = normalizeName(trimmedName);
        repository.findVisibleByName(workUnit.threadId(), workUnit.kind(), normalizedName)
                .filter(existing -> !existing.workUnitId().equals(workUnit.workUnitId()))
                .ifPresent(existing -> {
                    throw new IllegalStateException("duplicate WorkUnit name: " + trimmedName);
                });
        Instant now = Instant.now();
        WorkUnit saved = repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                trimmedName,
                normalizedName,
                workUnit.status(),
                workUnit.currentGoalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                workUnit.removedAt(),
                workUnit.createdAt(),
                now));
        currentGoal(saved).ifPresent(goal -> syncTeamSpace(saved, goal));
        return saved;
    }

    @Override
    @Transactional
    public WorkUnitConfig updateConfig(String workUnitId, String configJson, String structureJson) {
        if (workUnitId == null || workUnitId.isBlank()) {
            throw new IllegalArgumentException("工作容器 id 不能为空");
        }
        if (configJson == null || configJson.isBlank()) {
            throw new IllegalArgumentException("工作容器配置不能为空");
        }
        WorkUnit workUnit = repository.findById(workUnitId)
                .orElseThrow(() -> new IllegalArgumentException("工作容器不存在: " + workUnitId));
        if (workUnit.removed() || STATUS_REMOVED.equals(workUnit.status())) {
            throw new IllegalStateException("已移除的工作容器不能修改配置");
        }
        if (STATUS_RUNNING.equals(workUnit.status())) {
            throw new IllegalStateException("运行中的工作容器不能修改配置");
        }
        Instant now = Instant.now();
        if ("orchestration".equals(workUnit.kind())) {
            WorkUnitFlowConfigValidator.validateOrThrow(configJson, structureJson);
        }
        WorkUnitConfig existing = repository.findConfig(workUnitId).orElse(null);
        WorkUnitConfig saved = repository.saveConfig(new WorkUnitConfig(
                workUnitId,
                configJson.trim(),
                normalizeOptionalJson(structureJson),
                existing == null ? now : existing.createdAt(),
                now));
        WorkUnit savedWorkUnit = repository.save(new WorkUnit(
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
        currentGoal(savedWorkUnit).ifPresent(goal -> syncTeamSpace(savedWorkUnit, goal));
        return saved;
    }

    private static String normalizeOptionalJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return json.trim();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkUnitConfig> findConfig(String workUnitId) {
        if (workUnitId == null || workUnitId.isBlank()) {
            return Optional.empty();
        }
        return repository.findConfig(workUnitId);
    }

    @Override
    @Transactional
    public synchronized WorkUnitGoal selectPendingGoalForTurn(String threadId, String workUnitId) {
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
    public synchronized WorkUnitGoal ensurePendingGoalForReuse(String threadId, String workUnitId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be blank");
        }
        if (workUnitId == null || workUnitId.isBlank()) {
            throw new IllegalArgumentException("workUnitId must not be blank");
        }
        WorkUnit workUnit = repository.findById(workUnitId)
                .orElseThrow(() -> new IllegalArgumentException("WorkUnit does not exist: " + workUnitId));
        if (!threadId.equals(workUnit.threadId())) {
            throw new IllegalArgumentException("WorkUnit does not belong to thread: " + threadId);
        }
        if (workUnit.removed() || STATUS_REMOVED.equals(workUnit.status())) {
            throw new IllegalStateException("Removed WorkUnit cannot be prepared");
        }
        if (STATUS_RUNNING.equals(workUnit.status())) {
            throw new IllegalStateException("Running WorkUnit cannot be reconfigured");
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
                null,
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
    public int recoverAbandonedRunning() {
        List<WorkUnit> runningWorkUnits = repository.listVisibleByStatus(STATUS_RUNNING);
        Instant now = Instant.now();
        for (WorkUnit workUnit : runningWorkUnits) {
            failCurrentRunningGoal(workUnit, now);
            repository.save(new WorkUnit(
                    workUnit.workUnitId(),
                    workUnit.threadId(),
                    workUnit.kind(),
                    workUnit.name(),
                    workUnit.normalizedName(),
                    STATUS_FAILED,
                    workUnit.currentGoalId(),
                    workUnit.cwd(),
                    workUnit.sandboxMode(),
                    workUnit.removed(),
                    workUnit.removedAt(),
                    workUnit.createdAt(),
                    now));
        }
        return runningWorkUnits.size();
    }

    private void failCurrentRunningGoal(WorkUnit workUnit, Instant now) {
        String goalId = workUnit.currentGoalId();
        if (goalId == null || goalId.isBlank()) {
            return;
        }
        repository.findGoalById(goalId)
                .filter(goal -> GOAL_RUNNING.equals(goal.status()))
                .ifPresent(goal -> repository.saveGoal(new WorkUnitGoal(
                        goal.goalId(),
                        goal.workUnitId(),
                        goal.threadId(),
                        goal.goalText(),
                        GOAL_FAILED,
                        goal.runRefType(),
                        goal.runRefId(),
                        goal.summary(),
                        ABANDONED_RUNNING_MESSAGE,
                        goal.createdAt(),
                        goal.startedAt(),
                        now)));
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
        WorkUnit removed = repository.save(new WorkUnit(
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
        if ("team".equals(workUnit.kind()) && teamRepository != null) {
            teamRepository.markRemoved(teamIdForWorkUnit(workUnit.workUnitId()), now);
        }
        return removed;
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
        if (STATUS_FAILED.equals(workUnit.status()) || STATUS_COMPLETED.equals(workUnit.status())) {
            return createRerunGoal(workUnit, goals);
        }
        throw new IllegalStateException("工作容器没有可启动的 pending 目标: " + workUnit.workUnitId());
    }

    private WorkUnitGoal createRerunGoal(WorkUnit workUnit, List<WorkUnitGoal> goals) {
        WorkUnitGoal source = retrySourceGoal(workUnit, goals)
                .orElseThrow(() -> new IllegalStateException("工作容器没有可重新执行的目标: " + workUnit.workUnitId()));
        Instant now = Instant.now();
        WorkUnitGoal rerun = repository.saveGoal(new WorkUnitGoal(
                newGoalId(),
                workUnit.workUnitId(),
                workUnit.threadId(),
                source.goalText(),
                GOAL_PENDING,
                null,
                null,
                null,
                null,
                now,
                null,
                null));
        // 重新执行必须新建 pending 目标，旧终态目标保留为审计事实。
        repository.save(new WorkUnit(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.normalizedName(),
                STATUS_WAITING_CONFIG,
                rerun.goalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                workUnit.removedAt(),
                workUnit.createdAt(),
                now));
        return rerun;
    }

    private Optional<WorkUnitGoal> retrySourceGoal(WorkUnit workUnit, List<WorkUnitGoal> goals) {
        if (workUnit.currentGoalId() != null && !workUnit.currentGoalId().isBlank()) {
            for (WorkUnitGoal goal : goals) {
                if (workUnit.currentGoalId().equals(goal.goalId())) {
                    return Optional.of(goal);
                }
            }
        }
        for (int index = goals.size() - 1; index >= 0; index--) {
            WorkUnitGoal goal = goals.get(index);
            if (GOAL_FAILED.equals(goal.status()) || GOAL_COMPLETED.equals(goal.status())) {
                return Optional.of(goal);
            }
        }
        return goals.isEmpty() ? Optional.empty() : Optional.of(goals.getLast());
    }

    private void syncTeamSpace(WorkUnit workUnit, WorkUnitGoal goal) {
        if (teamRepository == null || workUnit == null || goal == null || !"team".equals(workUnit.kind())) {
            return;
        }
        if (STATUS_RUNNING.equals(workUnit.status())) {
            return;
        }
        String teamId = teamIdForWorkUnit(workUnit.workUnitId());
        TeamRecord existing = teamRepository.findByTeamId(teamId).orElse(null);
        List<TeamMemberRecord> existingMembers = teamRepository.listMembers(teamId);
        String configJson = repository.findConfig(workUnit.workUnitId())
                .map(WorkUnitConfig::configJson)
                .orElse(null);
        List<TeamMemberRecord> members = teamMembersForConfig(teamId, existingMembers, configJson);
        TeamRecord record = new TeamRecord(
                teamId,
                workUnit.threadId(),
                null,
                workUnit.name(),
                goal.goalText(),
                teamStatusForWorkUnit(workUnit.status()),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                existing != null && existing.approved(),
                existing != null && existing.frozen(),
                existing == null ? 4 : existing.maxRounds(),
                existing == null ? 0 : existing.currentRound(),
                existing == null || existing.currentAgent() == null || existing.currentAgent().isBlank()
                        ? "leader"
                        : existing.currentAgent(),
                existing == null ? null : existing.summary(),
                existing == null ? null : existing.errorMessage());
        teamRepository.save(record, members);
    }

    private void syncTeamSpaceIfNeeded(WorkUnit workUnit) {
        if (teamRepository == null || workUnit == null || !"team".equals(workUnit.kind())) {
            return;
        }
        currentGoal(workUnit).ifPresent(goal -> syncTeamSpace(workUnit, goal));
    }

    private Optional<WorkUnitGoal> currentGoal(WorkUnit workUnit) {
        if (workUnit.currentGoalId() == null || workUnit.currentGoalId().isBlank()) {
            return Optional.empty();
        }
        return repository.findGoalById(workUnit.currentGoalId());
    }

    private static List<TeamMemberRecord> ensureLeaderMember(String teamId, List<TeamMemberRecord> members) {
        List<TeamMemberRecord> next = new ArrayList<>();
        next.add(leaderMember(teamId));
        if (members != null) {
            members.stream()
                    .filter(member -> !"leader".equals(member.name()) && !"leader".equals(member.memberId()))
                    .forEach(next::add);
        }
        return next;
    }

    private static List<TeamMemberRecord> teamMembersForConfig(String teamId,
                                                               List<TeamMemberRecord> existingMembers,
                                                               String configJson) {
        List<TeamMemberRecord> fallback = ensureLeaderMember(teamId, existingMembers);
        List<TeamMemberConfig> configured = parseTeamMemberConfigs(configJson);
        if (configured.isEmpty()) {
            return fallback;
        }
        Map<String, TeamMemberRecord> existingByMemberId = existingMembers == null ? Map.of() : existingMembers.stream()
                .collect(Collectors.toMap(TeamMemberRecord::memberId, Function.identity(), (left, right) -> left));
        Map<String, TeamMemberRecord> existingByName = existingMembers == null ? Map.of() : existingMembers.stream()
                .collect(Collectors.toMap(TeamMemberRecord::name, Function.identity(), (left, right) -> left));
        List<TeamMemberRecord> next = new ArrayList<>();
        boolean hasLeader = false;
        int order = 0;
        for (TeamMemberConfig member : configured) {
            String memberId = blankToDefault(member.id(), "member_" + order);
            String name = blankToDefault(member.name(), memberId);
            TeamMemberRecord existing = Optional.ofNullable(existingByMemberId.get(memberId))
                    .orElse(existingByName.get(name));
            next.add(teamMemberRecord(teamId, member, memberId, name, existing, order));
            hasLeader = hasLeader || "leader".equals(memberId) || "leader".equals(name);
            order++;
        }
        if (!hasLeader) {
            next.add(0, leaderMember(teamId));
        }
        return next;
    }

    private static List<TeamMemberConfig> parseTeamMemberConfigs(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return List.of();
        }
        try {
            TeamConfigSnapshot snapshot = JSON.readValue(configJson, TeamConfigSnapshot.class);
            return snapshot.members() == null ? List.of() : snapshot.members().stream()
                    .filter(member -> member != null)
                    .toList();
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private static TeamMemberRecord teamMemberRecord(String teamId,
                                                     TeamMemberConfig member,
                                                     String memberId,
                                                     String name,
                                                     TeamMemberRecord existing,
                                                     int order) {
        String mode = "WORKSPACE_TOOL".equalsIgnoreCase(member.mode()) ? "WORKSPACE_TOOL" : "READ_ONLY_TOOL";
        List<String> toolNames = member.toolNames() == null || member.toolNames().isEmpty()
                ? ("WORKSPACE_TOOL".equals(mode) ? DEFAULT_WORKSPACE_TOOLS : DEFAULT_READ_TOOLS)
                : member.toolNames();
        return new TeamMemberRecord(
                teamId,
                memberId,
                name,
                name,
                blankToDefault(member.role(), name),
                mode,
                String.join(",", toolNames),
                existing == null || existing.status() == null || existing.status().isBlank() ? "pending" : existing.status(),
                order,
                existing == null ? 0 : existing.toolCallCount(),
                existing == null ? 0 : existing.tokenEstimate(),
                existing == null ? null : existing.summary());
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static TeamMemberRecord leaderMember(String teamId) {
        return new TeamMemberRecord(
                teamId,
                "leader",
                "leader",
                "leader",
                "leader",
                "READ_ONLY_TOOL",
                "",
                "pending",
                0,
                0,
                0,
                null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TeamConfigSnapshot(List<TeamMemberConfig> members) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TeamMemberConfig(String id,
                                    String name,
                                    String role,
                                    String task,
                                    String mode,
                                    List<String> toolNames,
                                    List<String> writeScopes) {
    }

    private static String teamStatusForWorkUnit(String status) {
        if (STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_RUNNING.equals(status)) {
            return status;
        }
        return "pending";
    }

    private static String teamIdForWorkUnit(String workUnitId) {
        return "team_" + workUnitId.replaceFirst("^wu_", "");
    }

    private static String newWorkUnitId() {
        return "wu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String newGoalId() {
        return "goal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
