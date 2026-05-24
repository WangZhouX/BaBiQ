package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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
        entity.setId(existing.getId());
        turnMapper.updateById(entity);
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

    /**
     * 按 turnId 查询持久化记录。
     *
     * @param turnId 协议层 turn id
     * @return 找到时返回实体
     */
    public Optional<TurnEntity> findTurn(String turnId) {
        return Optional.ofNullable(turnMapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId)));
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
        var query = Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getThreadId, threadId)
                .orderByDesc(TurnEntity::getStartedAt)
                .last("LIMIT " + safeLimit);
        if (beforeTurnId != null && !beforeTurnId.isBlank()) {
            findTurn(beforeTurnId).ifPresent(before -> query.lt(TurnEntity::getStartedAt, before.getStartedAt()));
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
        return entity;
    }
}
