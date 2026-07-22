package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ToolCallRecord;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.persistence.entity.ToolCallEntity;
import com.wzx.babiq.server.persistence.mapper.ToolCallMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具调用持久化服务。
 *
 * <p>ToolObservationInterceptor 只负责拦截 SAA 工具链路，不应该直接拼数据库条件；
 * 本服务把“开始记录、完成更新、按 turn 查询”集中起来，后续换表结构时不会影响拦截器。</p>
 */
@Service
public class ToolCallPersistenceService {

    /** 工具结果预览最大长度，防止大文件或命令输出撑爆运行记录面板。 */
    private static final int PREVIEW_LIMIT = 1_000;

    /** 工具调用表 mapper。 */
    private final ToolCallMapper toolCallMapper;

    /**
     * 创建工具调用持久化服务。
     *
     * @param toolCallMapper 工具调用 mapper
     */
    public ToolCallPersistenceService(ToolCallMapper toolCallMapper) {
        this.toolCallMapper = toolCallMapper;
    }

    /**
     * 记录工具调用开始。
     *
     * @param toolCallId SAA 工具调用 id
     * @param threadId 所属 thread
     * @param turnId 所属 turn
     * @param toolName 工具名
     * @param argsJson 工具参数 JSON
     * @param startedAt 开始时间
     */
    @Transactional
    public void recordStarted(String toolCallId, String threadId, String turnId,
                              String toolName, String argsJson, Instant startedAt) {
        recordStarted(toolCallId, threadId, turnId, toolName, argsJson,
                "babiq_agent", null, null, startedAt);
    }

    /**
     * 记录带 Agent 归属信息的工具调用开始。
     *
     * @param toolCallId SAA 工具调用 id
     * @param threadId 所属 thread
     * @param turnId 所属 turn
     * @param toolName 工具名
     * @param argsJson 工具参数 JSON
     * @param agentName 实际执行该工具的 Agent 名称；主 Agent 默认为 babiq_agent
     * @param parentAgentName 委派来源 Agent；主 Agent 直接调用时为空
     * @param delegationId 子 Agent 委派 id；非委派调用时为空
     * @param startedAt 开始时间
     */
    @Transactional
    public void recordStarted(String toolCallId, String threadId, String turnId,
                              String toolName, String argsJson,
                              String agentName, String parentAgentName,
                              String delegationId, Instant startedAt) {
        recordStartedInternal(toolCallId, threadId, turnId, toolName, argsJson,
                agentName, parentAgentName, delegationId, BusinessIdentityScope.UNSCOPED, startedAt);
    }

    @Transactional
    public void recordStarted(String toolCallId, String threadId, String turnId,
                              String toolName, String argsJson, String agentName,
                              String parentAgentName, String delegationId,
                              BusinessIdentityScope scope, Instant startedAt) {
        recordStartedInternal(toolCallId, threadId, turnId, toolName, argsJson,
                agentName, parentAgentName, delegationId, scope, startedAt);
    }

    private void recordStartedInternal(String toolCallId, String threadId, String turnId,
                                       String toolName, String argsJson, String agentName,
                                       String parentAgentName, String delegationId,
                                       BusinessIdentityScope scope, Instant startedAt) {
        ToolCallEntity entity = new ToolCallEntity();
        entity.setToolCallId(toolCallId);
        entity.setThreadId(threadId);
        entity.setTurnId(turnId);
        entity.setToolName(toolName);
        entity.setArgsJson(argsJson == null ? "{}" : argsJson);
        entity.setAgentName(agentName == null || agentName.isBlank() ? "babiq_agent" : agentName);
        entity.setParentAgentName(parentAgentName);
        entity.setDelegationId(delegationId);
        entity.setStatus("running");
        entity.setStartedAt(PersistenceTime.write(startedAt));
        applyScope(entity, scope);
        ToolCallEntity existing = findEntity(turnId, toolCallId);
        if (existing == null) {
            toolCallMapper.insert(entity);
            return;
        }
        if (!sameImmutableMetadata(existing, entity)) {
            throw new IllegalStateException("tool call immutable metadata conflict");
        }
    }

    private static void applyScope(ToolCallEntity entity, BusinessIdentityScope scope) {
        if (entity == null || scope == null || !scope.scoped()) return;
        entity.setDesktopInstanceId(scope.desktopInstanceId()); entity.setDesktopSessionId(scope.desktopSessionId());
        entity.setAuthSessionId(scope.authSessionId()); entity.setIdentityEpoch(scope.identityEpoch());
        entity.setUserId(scope.userId()); entity.setTenantId(scope.tenantId()); entity.setPlatformId(scope.platformId());
    }

    /**
     * 记录工具调用完成或失败。
     *
     * @param turnId 所属 turn
     * @param toolCallId SAA 工具调用 id
     * @param status completed、failed 或 denied
     * @param resultPreview 成功结果短预览
     * @param errorMessage 错误或拒绝原因
     * @param completedAt 完成时间
     */
    @Transactional
    public void recordFinished(String turnId, String toolCallId, String status, String resultPreview,
                               String errorMessage, Instant completedAt) {
        ToolCallEntity existing = findEntity(turnId, toolCallId);
        if (existing == null) {
            return;
        }
        existing.setStatus(status);
        existing.setResultPreview(truncate(resultPreview));
        existing.setErrorMessage(truncate(errorMessage));
        existing.setCompletedAt(PersistenceTime.write(completedAt));
        toolCallMapper.updateById(existing);
    }

    /**
     * 按 turn 查询工具调用记录。
     *
     * @param turnId 运行回合 id
     * @return 按开始时间正序排列的工具调用记录
     */
    public List<ToolCallRecord> listByTurnId(String turnId) {
        return listByTurnId(turnId, BusinessIdentityScope.UNSCOPED);
    }

    public List<ToolCallRecord> listByTurnId(String turnId, BusinessIdentityScope scope) {
        return toolCallMapper.selectAuthorizedByTurnId(
                        turnId, scoped(scope), desktopInstanceId(scope), desktopSessionId(scope), authSessionId(scope),
                        identityEpoch(scope), userId(scope), tenantId(scope), platformId(scope))
                .stream()
                .map(this::toRecord)
                .sorted(Comparator.comparing(ToolCallRecord::startedAt))
                .toList();
    }

    @Transactional
    public void bindExecutionId(String turnId, String toolCallId,
                                BusinessIdentityScope scope, String executionId) {
        ToolCallEntity existing = findEntity(turnId, toolCallId, scope).orElse(null);
        if (existing == null) {
            throw new IllegalStateException("tool call execution binding conflict");
        }
        if (Objects.equals(existing.getExecutionId(), executionId)) {
            return;
        }
        if (existing.getExecutionId() != null) {
            throw new IllegalStateException("tool call execution binding conflict");
        }
        int updated = toolCallMapper.bindExecutionIdIfUnbound(
                turnId, toolCallId, executionId, scoped(scope), desktopInstanceId(scope), desktopSessionId(scope),
                authSessionId(scope), identityEpoch(scope), userId(scope), tenantId(scope), platformId(scope));
        if (updated == 0 && !findEntity(turnId, toolCallId, scope)
                .map(entity -> Objects.equals(entity.getExecutionId(), executionId)).orElse(false)) {
            throw new IllegalStateException("tool call execution binding conflict");
        }
    }

    private static int scoped(BusinessIdentityScope scope) { return scope != null && scope.scoped() ? 1 : 0; }
    private static String desktopInstanceId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.desktopInstanceId() : null; }
    private static String desktopSessionId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.desktopSessionId() : null; }
    private static String authSessionId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.authSessionId() : null; }
    private static Long identityEpoch(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.identityEpoch() : null; }
    private static String userId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.userId() : null; }
    private static String tenantId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.tenantId() : null; }
    private static String platformId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.platformId() : null; }

    private ToolCallEntity findEntity(String turnId, String toolCallId) {
        return toolCallMapper.selectOne(Wrappers.<ToolCallEntity>lambdaQuery()
                .eq(ToolCallEntity::getTurnId, turnId)
                .eq(ToolCallEntity::getToolCallId, toolCallId));
    }

    private Optional<ToolCallEntity> findEntity(String turnId, String toolCallId,
                                                BusinessIdentityScope scope) {
        var query = Wrappers.<ToolCallEntity>lambdaQuery()
                .eq(ToolCallEntity::getTurnId, turnId)
                .eq(ToolCallEntity::getToolCallId, toolCallId);
        if (scope == null || !scope.scoped()) {
            query.isNull(ToolCallEntity::getDesktopInstanceId);
        } else {
            query.eq(ToolCallEntity::getDesktopInstanceId, scope.desktopInstanceId())
                    .eq(ToolCallEntity::getDesktopSessionId, scope.desktopSessionId())
                    .eq(ToolCallEntity::getAuthSessionId, scope.authSessionId())
                    .eq(ToolCallEntity::getIdentityEpoch, scope.identityEpoch())
                    .eq(ToolCallEntity::getUserId, scope.userId())
                    .eq(ToolCallEntity::getTenantId, scope.tenantId())
                    .eq(ToolCallEntity::getPlatformId, scope.platformId());
        }
        return Optional.ofNullable(toolCallMapper.selectOne(query));
    }

    private static boolean sameImmutableMetadata(ToolCallEntity existing, ToolCallEntity candidate) {
        return Objects.equals(existing.getThreadId(), candidate.getThreadId())
                && Objects.equals(existing.getTurnId(), candidate.getTurnId())
                && Objects.equals(existing.getToolName(), candidate.getToolName())
                && Objects.equals(existing.getArgsJson(), candidate.getArgsJson())
                && Objects.equals(existing.getAgentName(), candidate.getAgentName())
                && Objects.equals(existing.getParentAgentName(), candidate.getParentAgentName())
                && Objects.equals(existing.getDelegationId(), candidate.getDelegationId())
                && Objects.equals(existing.getStartedAt(), candidate.getStartedAt())
                && Objects.equals(existing.getDesktopInstanceId(), candidate.getDesktopInstanceId())
                && Objects.equals(existing.getDesktopSessionId(), candidate.getDesktopSessionId())
                && Objects.equals(existing.getAuthSessionId(), candidate.getAuthSessionId())
                && Objects.equals(existing.getIdentityEpoch(), candidate.getIdentityEpoch())
                && Objects.equals(existing.getUserId(), candidate.getUserId())
                && Objects.equals(existing.getTenantId(), candidate.getTenantId())
                && Objects.equals(existing.getPlatformId(), candidate.getPlatformId());
    }

    private ToolCallRecord toRecord(ToolCallEntity entity) {
        return new ToolCallRecord(
                entity.getToolCallId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getToolName(),
                entity.getArgsJson(),
                entity.getStatus(),
                entity.getResultPreview(),
                entity.getErrorMessage(),
                entity.getAgentName(),
                entity.getParentAgentName(),
                entity.getDelegationId(),
                PersistenceTime.read(entity.getStartedAt()),
                PersistenceTime.read(entity.getCompletedAt()));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= PREVIEW_LIMIT) {
            return value;
        }
        return value.substring(0, PREVIEW_LIMIT) + "...";
    }
}
