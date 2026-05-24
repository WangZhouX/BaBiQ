package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
