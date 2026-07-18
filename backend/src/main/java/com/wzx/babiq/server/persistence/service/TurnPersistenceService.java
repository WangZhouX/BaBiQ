package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turn 表的持久化服务。
 *
 * <p>该服务先提供最小保存能力，后续 P2-4 的恢复语义会在这里扩展“查找未完成 turn”和“写入恢复结果”。</p>
 */
@Service
public class TurnPersistenceService {

    /** turn 单表 mapper，负责把领域记录落到 `bq_turns`。 */
    private final TurnMapper turnMapper;

    /**
     * 创建 TurnPersistenceService。
     *
     * @param turnMapper turn 单表 mapper
     */
    public TurnPersistenceService(TurnMapper turnMapper) {
        this.turnMapper = turnMapper;
    }

    /**
     * 保存或更新 turn 记录。
     *
     * @param record turn 领域记录
     */
    @Transactional
    public void saveTurn(TurnRecord record) {
        TurnEntity entity = toEntity(record);
        TurnEntity existing = turnMapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, record.turnId()));
        if (existing == null) {
            turnMapper.insert(entity);
            return;
        }
        if (!sameImmutableMetadata(existing, entity)) {
            throw new IllegalStateException("turn immutable metadata conflict");
        }
    }

    /**
     * 更新 turn 的终态。
     *
     * <p>P2-2 的实时事件由 ItemEmitter 发出，因此终态更新也在事件发送前完成，避免 UI 收到完成事件后
     * 数据库仍停留在 RUNNING。</p>
     *
     * @param turnId 协议层 turn id
     * @param status 终态状态，例如 COMPLETED、FAILED、CANCELED
     * @param failureReason 失败原因；非失败状态为空
     */
    @Transactional
    public void updateTurnStatus(String turnId, String status, String failureReason) {
        TurnEntity existing = turnMapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId));
        if (existing == null) {
            return;
        }
        existing.setStatus(status);
        existing.setCompletedAt(PersistenceTime.write(Instant.now()));
        existing.setFailureReason(failureReason);
        turnMapper.updateById(existing);
    }

    /**
     * 把 turn 标记为等待审批，但不写 completedAt。
     *
     * <p>WAITING_APPROVAL 不是终态，用户稍后仍可能通过 approval/respond 恢复执行，
     * 因此它只更新状态，不应让运行记录误以为 turn 已结束。</p>
     *
     * @param turnId 协议层 turn id
     */
    @Transactional
    public void markWaitingApproval(String turnId) {
        TurnEntity existing = findTurn(turnId).orElse(null);
        if (existing == null) {
            return;
        }
        existing.setStatus("WAITING_APPROVAL");
        turnMapper.updateById(existing);
    }

    /**
     * 把被恢复流程收口的 turn 写成可解释终态。
     *
     * @param turnId 协议层 turn id
     * @param status 恢复后的终态，例如 INTERRUPTED 或 EXPIRED
     * @param reason 恢复原因，会同时写入 recoveryReason 和 failureReason
     * @param recoveredAt 恢复发生时间
     */
    @Transactional
    public void markRecovered(String turnId, String status, String reason, Instant recoveredAt) {
        TurnEntity existing = findTurn(turnId).orElse(null);
        if (existing == null) {
            return;
        }
        String timestamp = PersistenceTime.write(recoveredAt);
        existing.setStatus(status);
        existing.setFailureReason(reason);
        existing.setRecoveryReason(reason);
        existing.setRecoveredAt(timestamp);
        existing.setCompletedAt(timestamp);
        turnMapper.updateById(existing);
    }

    /**
     * 把用户主动取消或中断写入 turn 记录。
     *
     * @param turnId 协议层 turn id
     * @param status 取消后的状态，通常是 CANCELED 或 INTERRUPTED
     * @param cancelReason 取消原因
     */
    @Transactional
    public void markCanceled(String turnId, String status, String cancelReason) {
        TurnEntity existing = findTurn(turnId).orElse(null);
        if (existing == null) {
            return;
        }
        existing.setStatus(status);
        existing.setCancelReason(cancelReason);
        existing.setCompletedAt(PersistenceTime.write(Instant.now()));
        turnMapper.updateById(existing);
    }

    @Transactional
    public boolean markCanceled(String turnId, String status, String cancelReason, BusinessIdentityScope scope) {
        TurnEntity existing = findTurn(turnId, scope).orElse(null);
        if (existing == null) return false;
        existing.setStatus(status);
        existing.setCancelReason(cancelReason);
        existing.setCompletedAt(PersistenceTime.write(Instant.now()));
        turnMapper.updateById(existing);
        return true;
    }

    /** 按不可变业务作用域只收束尚未执行的 turn，RUNNING 不受身份切换影响。 */
    @Transactional
    public boolean expirePreExecutionTurn(
            String turnId, BusinessIdentityScope scope, String reason) {
        if (scope == null || !scope.scoped()) {
            return false;
        }
        String timestamp = PersistenceTime.write(Instant.now());
        return turnMapper.expirePreExecutionIfCurrent(
                turnId, scope.desktopInstanceId(), scope.desktopSessionId(), scope.authSessionId(),
                scope.identityEpoch(), scope.userId(), scope.tenantId(), scope.platformId(),
                reason, timestamp) == 1;
    }

    /** 按冻结身份和期望前置状态，把 turn 原子推进到 RUNNING。 */
    @Transactional
    public boolean transitionPreExecutionToRunning(
            String turnId, BusinessIdentityScope scope, String expectedStatus) {
        if (scope == null || !scope.scoped()
                || expectedStatus == null
                || (!"CREATED".equals(expectedStatus) && !"WAITING_APPROVAL".equals(expectedStatus))) {
            return false;
        }
        return turnMapper.transitionPreExecutionToRunningIfCurrent(
                turnId, scope.desktopInstanceId(), scope.desktopSessionId(), scope.authSessionId(),
                scope.identityEpoch(), scope.userId(), scope.tenantId(), scope.platformId(),
                expectedStatus) == 1;
    }

    /** 仅把仍处于 RUNNING 的同一冻结作用域 turn 原子收口为 FAILED。 */
    @Transactional
    public boolean failRunningTurn(String turnId, BusinessIdentityScope scope, String reason) {
        if (turnId == null || turnId.isBlank() || reason == null || reason.isBlank()) {
            return false;
        }
        String completedAt = PersistenceTime.write(Instant.now());
        if (scope != null && scope.scoped()) {
            return turnMapper.failScopedRunningIfCurrent(
                    turnId, scope.desktopInstanceId(), scope.desktopSessionId(), scope.authSessionId(),
                    scope.identityEpoch(), scope.userId(), scope.tenantId(), scope.platformId(),
                    reason, completedAt) == 1;
        }
        return turnMapper.failUnscopedRunningIfCurrent(turnId, reason, completedAt) == 1;
    }

    /** 仅把仍处于 RUNNING 的同一冻结作用域 turn 原子收口为 COMPLETED。 */
    @Transactional
    public boolean completeRunningTurn(String turnId, BusinessIdentityScope scope) {
        if (turnId == null || turnId.isBlank()) {
            return false;
        }
        String completedAt = PersistenceTime.write(Instant.now());
        if (scope != null && scope.scoped()) {
            return turnMapper.completeScopedRunningIfCurrent(
                    turnId, scope.desktopInstanceId(), scope.desktopSessionId(), scope.authSessionId(),
                    scope.identityEpoch(), scope.userId(), scope.tenantId(), scope.platformId(),
                    completedAt) == 1;
        }
        return turnMapper.completeUnscopedRunningIfCurrent(turnId, completedAt) == 1;
    }

    /**
     * 按 turnId 查询持久化记录。
     *
     * @param turnId 协议层 turn id
     * @return 找到时返回实体
     */
    public Optional<TurnEntity> findTurn(String turnId) {
        return Optional.ofNullable(turnMapper.selectOne(scopedQuery(BusinessIdentityScope.UNSCOPED)
                .eq(TurnEntity::getTurnId, turnId)));
    }

    public Optional<TurnEntity> findTurn(String turnId, BusinessIdentityScope scope) {
        return Optional.ofNullable(turnMapper.selectOne(scopedQuery(scope)
                .eq(TurnEntity::getTurnId, turnId)));
    }

    /** 查询完整冻结身份下仍可被身份切换过期的持久化 turn。 */
    public List<TurnEntity> findPreExecutionCandidates(BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) {
            return List.of();
        }
        return turnMapper.selectList(scopedQuery(scope)
                .in(TurnEntity::getStatus, "CREATED", "WAITING_APPROVAL")
                .orderByAsc(TurnEntity::getStartedAt));
    }

    /**
     * 查询指定状态集合内的 turn，恢复服务用它找出上次进程遗留的非终态记录。
     *
     * @param statuses 状态集合
     * @return 匹配的 turn 列表
     */
    public List<TurnEntity> findByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return turnMapper.selectList(Wrappers.<TurnEntity>lambdaQuery()
                .in(TurnEntity::getStatus, statuses)
                .orderByAsc(TurnEntity::getStartedAt));
    }

    /**
     * 查询某个 thread 下的运行回合，按 startedAt 倒序返回。
     *
     * @param threadId 会话 id
     * @param limit 最大数量
     * @param beforeTurnId 可选游标；非空时读取该 turn 之前的更早记录
     * @return turn 实体列表
     */
    public List<TurnEntity> listTurns(String threadId, int limit, String beforeTurnId) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        var query = scopedQuery(BusinessIdentityScope.UNSCOPED)
                .eq(TurnEntity::getThreadId, threadId)
                .orderByDesc(TurnEntity::getStartedAt)
                .last("LIMIT " + safeLimit);
        if (beforeTurnId != null && !beforeTurnId.isBlank()) {
            findTurn(beforeTurnId).ifPresent(before -> query.lt(TurnEntity::getStartedAt, before.getStartedAt()));
        }
        return turnMapper.selectList(query);
    }

    public List<TurnEntity> listTurns(
            String threadId, int limit, String beforeTurnId, BusinessIdentityScope scope) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        var query = scopedQuery(scope).eq(TurnEntity::getThreadId, threadId)
                .orderByDesc(TurnEntity::getStartedAt).last("LIMIT " + safeLimit);
        if (beforeTurnId != null && !beforeTurnId.isBlank()) {
            findTurn(beforeTurnId, scope).ifPresent(before ->
                    query.lt(TurnEntity::getStartedAt, before.getStartedAt()));
        }
        return turnMapper.selectList(query);
    }

    private TurnEntity toEntity(TurnRecord record) {
        TurnEntity entity = new TurnEntity();
        entity.setTurnId(record.turnId());
        entity.setThreadId(record.threadId());
        entity.setStatus(record.status());
        entity.setInputText(record.inputText());
        entity.setCwd(record.cwd());
        entity.setProviderId(record.providerId());
        entity.setModel(record.model());
        entity.setSandboxMode(record.sandboxMode());
        entity.setApprovalPolicy(record.approvalPolicy());
        entity.setStartedAt(PersistenceTime.write(record.startedAt()));
        entity.setCompletedAt(PersistenceTime.write(record.completedAt()));
        entity.setFailureReason(record.failureReason());
        applyScope(entity, record.businessIdentityScope());
        return entity;
    }

    private static boolean sameImmutableMetadata(TurnEntity existing, TurnEntity candidate) {
        return Objects.equals(existing.getThreadId(), candidate.getThreadId())
                && Objects.equals(existing.getInputText(), candidate.getInputText())
                && Objects.equals(existing.getCwd(), candidate.getCwd())
                && Objects.equals(existing.getProviderId(), candidate.getProviderId())
                && Objects.equals(existing.getModel(), candidate.getModel())
                && Objects.equals(existing.getSandboxMode(), candidate.getSandboxMode())
                && Objects.equals(existing.getApprovalPolicy(), candidate.getApprovalPolicy())
                && Objects.equals(existing.getStartedAt(), candidate.getStartedAt())
                && Objects.equals(existing.getDesktopInstanceId(), candidate.getDesktopInstanceId())
                && Objects.equals(existing.getDesktopSessionId(), candidate.getDesktopSessionId())
                && Objects.equals(existing.getAuthSessionId(), candidate.getAuthSessionId())
                && Objects.equals(existing.getIdentityEpoch(), candidate.getIdentityEpoch())
                && Objects.equals(existing.getUserId(), candidate.getUserId())
                && Objects.equals(existing.getTenantId(), candidate.getTenantId())
                && Objects.equals(existing.getPlatformId(), candidate.getPlatformId());
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TurnEntity> scopedQuery(
            BusinessIdentityScope scope) {
        var query = Wrappers.<TurnEntity>lambdaQuery();
        if (scope == null || !scope.scoped()) return query.isNull(TurnEntity::getDesktopInstanceId);
        return query.eq(TurnEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(TurnEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(TurnEntity::getAuthSessionId, scope.authSessionId())
                .eq(TurnEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(TurnEntity::getUserId, scope.userId()).eq(TurnEntity::getTenantId, scope.tenantId())
                .eq(TurnEntity::getPlatformId, scope.platformId());
    }

    private static void applyScope(TurnEntity entity, BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) return;
        entity.setDesktopInstanceId(scope.desktopInstanceId()); entity.setDesktopSessionId(scope.desktopSessionId());
        entity.setAuthSessionId(scope.authSessionId()); entity.setIdentityEpoch(scope.identityEpoch());
        entity.setUserId(scope.userId()); entity.setTenantId(scope.tenantId()); entity.setPlatformId(scope.platformId());
    }
}
