package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.context.repository.ContextCompactionRecord;
import com.wzx.babiq.server.context.repository.ContextCompactionRepository;
import com.wzx.babiq.server.persistence.entity.ContextCompactionEntity;
import com.wzx.babiq.server.persistence.mapper.ContextCompactionMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ContextCompactionRepository 的 SQLite 实现。
 *
 * <p>它只追加压缩审计记录，不参与当前 active window 的选择。</p>
 */
@Repository
public class SQLiteContextCompactionRepository implements ContextCompactionRepository {

    /** 压缩审计表 mapper。 */
    private final ContextCompactionMapper mapper;

    /**
     * 创建 SQLite 压缩审计仓库。
     *
     * @param mapper MyBatis-Plus mapper
     */
    public SQLiteContextCompactionRepository(ContextCompactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ContextCompactionRecord save(ContextCompactionRecord record) {
        mapper.insert(toEntity(record));
        return record;
    }

    @Override
    public long countByThreadId(String threadId) {
        return mapper.selectCount(Wrappers.<ContextCompactionEntity>lambdaQuery()
                .eq(ContextCompactionEntity::getThreadId, threadId));
    }

    @Override
    public Optional<ContextCompactionRecord> findLatestByThreadId(String threadId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ContextCompactionEntity>lambdaQuery()
                        .eq(ContextCompactionEntity::getThreadId, threadId)
                        .orderByDesc(ContextCompactionEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    public List<ContextCompactionRecord> findRecoverableSince(Instant since) {
        String sinceText = PersistenceTime.write(since);
        return mapper.selectList(Wrappers.<ContextCompactionEntity>lambdaQuery()
                        .ge(since != null, ContextCompactionEntity::getCreatedAt, sinceText)
                        .and(wrapper -> wrapper
                                .isNotNull(ContextCompactionEntity::getStartedAt)
                                .isNull(ContextCompactionEntity::getCompletedAt)
                                .or()
                                .eq(ContextCompactionEntity::getStatus, "SUCCESS")
                                .isNotNull(ContextCompactionEntity::getSummaryId))
                        .orderByDesc(ContextCompactionEntity::getCreatedAt))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    @Transactional
    public void updateStatus(String compactionId, String status, String errorMessage, Instant completedAt) {
        ContextCompactionEntity existing = mapper.selectOne(Wrappers.<ContextCompactionEntity>lambdaQuery()
                .eq(ContextCompactionEntity::getCompactionId, compactionId));
        if (existing == null) {
            return;
        }
        existing.setStatus(status);
        existing.setErrorMessage(errorMessage);
        existing.setCompletedAt(PersistenceTime.write(completedAt == null ? Instant.now() : completedAt));
        mapper.updateById(existing);
    }

    private ContextCompactionEntity toEntity(ContextCompactionRecord record) {
        ContextCompactionEntity entity = new ContextCompactionEntity();
        entity.setCompactionId(record.compactionId());
        entity.setThreadId(record.threadId());
        entity.setTurnId(record.turnId());
        entity.setStatus(record.status());
        entity.setSummaryId(record.summaryId());
        entity.setSourceItemRange(record.sourceItemRange());
        entity.setSourceStartItemId(record.sourceStartItemId());
        entity.setSourceEndItemId(record.sourceEndItemId());
        entity.setEstimatedTokensBefore(record.estimatedTokensBefore());
        entity.setEstimatedTokensAfter(record.estimatedTokensAfter());
        entity.setErrorMessage(record.errorMessage());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setTriggerType(record.triggerType());
        entity.setPreviousWindowOrdinal(record.previousWindowOrdinal());
        entity.setNextWindowOrdinal(record.nextWindowOrdinal());
        entity.setInputSnapshotId(record.inputSnapshotId());
        entity.setReplacementSnapshotId(record.replacementSnapshotId());
        entity.setModelContextWindow(record.modelContextWindow());
        entity.setEffectiveInputBudget(record.effectiveInputBudget());
        entity.setAutoCompactThreshold(record.autoCompactThreshold());
        entity.setStartedAt(PersistenceTime.write(record.startedAt()));
        entity.setCompletedAt(PersistenceTime.write(record.completedAt()));
        return entity;
    }

    private ContextCompactionRecord toRecord(ContextCompactionEntity entity) {
        return new ContextCompactionRecord(
                entity.getCompactionId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getStatus(),
                entity.getSummaryId(),
                entity.getSourceItemRange(),
                entity.getSourceStartItemId(),
                entity.getSourceEndItemId(),
                entity.getEstimatedTokensBefore() == null ? 0 : entity.getEstimatedTokensBefore(),
                entity.getEstimatedTokensAfter() == null ? 0 : entity.getEstimatedTokensAfter(),
                entity.getErrorMessage(),
                PersistenceTime.read(entity.getCreatedAt()),
                entity.getTriggerType(),
                entity.getPreviousWindowOrdinal(),
                entity.getNextWindowOrdinal(),
                entity.getInputSnapshotId(),
                entity.getReplacementSnapshotId(),
                entity.getModelContextWindow(),
                entity.getEffectiveInputBudget(),
                entity.getAutoCompactThreshold(),
                PersistenceTime.read(entity.getStartedAt()),
                PersistenceTime.read(entity.getCompletedAt()));
    }
}
