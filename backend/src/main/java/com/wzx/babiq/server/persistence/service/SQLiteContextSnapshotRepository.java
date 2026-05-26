package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.persistence.entity.ContextSnapshotEntity;
import com.wzx.babiq.server.persistence.mapper.ContextSnapshotMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ContextSnapshotRepository 的 SQLite 实现。
 *
 * <p>该实现把快照写入 `bq_context_snapshots`，并提供按 turn/thread/snapshot id 查询的审计入口。</p>
 */
@Repository
public class SQLiteContextSnapshotRepository implements ContextSnapshotRepository {

    /** 上下文快照表 mapper，负责 `bq_context_snapshots` 单表读写。 */
    private final ContextSnapshotMapper mapper;

    /**
     * 创建 SQLite 上下文快照仓库。
     *
     * @param mapper MyBatis-Plus mapper
     */
    public SQLiteContextSnapshotRepository(ContextSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(ContextSnapshotRecord record) {
        ContextSnapshotEntity entity = toEntity(record);
        ContextSnapshotEntity existing = mapper.selectOne(Wrappers.<ContextSnapshotEntity>lambdaQuery()
                .eq(ContextSnapshotEntity::getSnapshotId, record.snapshotId()));
        if (existing == null) {
            mapper.insert(entity);
            return;
        }
        entity.setId(existing.getId());
        mapper.updateById(entity);
    }

    @Override
    public Optional<ContextSnapshotRecord> findBySnapshotId(String snapshotId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ContextSnapshotEntity>lambdaQuery()
                        .eq(ContextSnapshotEntity::getSnapshotId, snapshotId)))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findLatestByTurnId(String turnId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ContextSnapshotEntity>lambdaQuery()
                        .eq(ContextSnapshotEntity::getTurnId, turnId)
                        .orderByDesc(ContextSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findLatestByThreadId(String threadId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ContextSnapshotEntity>lambdaQuery()
                        .eq(ContextSnapshotEntity::getThreadId, threadId)
                        .orderByDesc(ContextSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    @Transactional
    public void updateActualPromptTokens(String snapshotId, Long actualPromptTokens) {
        ContextSnapshotEntity existing = mapper.selectOne(Wrappers.<ContextSnapshotEntity>lambdaQuery()
                .eq(ContextSnapshotEntity::getSnapshotId, snapshotId));
        if (existing == null) {
            return;
        }
        existing.setActualPromptTokens(actualPromptTokens);
        mapper.updateById(existing);
    }

    private ContextSnapshotRecord toRecord(ContextSnapshotEntity entity) {
        return new ContextSnapshotRecord(
                entity.getSnapshotId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getPhase(),
                entity.getProviderId(),
                entity.getModel(),
                entity.getCwd(),
                entity.getWindowOrdinal() == null ? 0 : entity.getWindowOrdinal(),
                entity.getModelContextWindow() == null ? 0 : entity.getModelContextWindow(),
                entity.getAutoCompactThreshold() == null ? 0 : entity.getAutoCompactThreshold(),
                entity.getEstimatedTokens() == null ? 0 : entity.getEstimatedTokens(),
                entity.getActualPromptTokens(),
                entity.getIncludedItemCount() == null ? 0 : entity.getIncludedItemCount(),
                entity.getExcludedItemCount() == null ? 0 : entity.getExcludedItemCount(),
                entity.getEnvelopeJson(),
                entity.getItemsJson(),
                entity.getCapabilityCatalogJson(),
                entity.getLongTermMemoryRefsJson(),
                entity.getLongTermMemoryTokenEstimate() == null ? 0 : entity.getLongTermMemoryTokenEstimate(),
                entity.getInputPreview(),
                PersistenceTime.read(entity.getCreatedAt()));
    }

    private ContextSnapshotEntity toEntity(ContextSnapshotRecord record) {
        ContextSnapshotEntity entity = new ContextSnapshotEntity();
        entity.setSnapshotId(record.snapshotId());
        entity.setThreadId(record.threadId());
        entity.setTurnId(record.turnId());
        entity.setPhase(record.phase());
        entity.setProviderId(record.providerId());
        entity.setModel(record.model());
        entity.setCwd(record.cwd());
        entity.setWindowOrdinal(record.windowOrdinal());
        entity.setModelContextWindow(record.modelContextWindow());
        entity.setAutoCompactThreshold(record.autoCompactThreshold());
        entity.setEstimatedTokens(record.estimatedTokens());
        entity.setActualPromptTokens(record.actualPromptTokens());
        entity.setIncludedItemCount(record.includedItemCount());
        entity.setExcludedItemCount(record.excludedItemCount());
        entity.setEnvelopeJson(record.envelopeJson());
        entity.setItemsJson(record.itemsJson());
        entity.setCapabilityCatalogJson(record.capabilityCatalogJson());
        entity.setLongTermMemoryRefsJson(record.longTermMemoryRefsJson());
        entity.setLongTermMemoryTokenEstimate(record.longTermMemoryTokenEstimate());
        entity.setInputPreview(record.inputPreview());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        return entity;
    }
}
