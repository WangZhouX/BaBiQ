package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.context.repository.ContextSummaryRecord;
import com.wzx.babiq.server.context.repository.ContextSummaryRepository;
import com.wzx.babiq.server.persistence.entity.ContextSummaryEntity;
import com.wzx.babiq.server.persistence.mapper.ContextSummaryMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ContextSummaryRepository 的 SQLite 实现。
 *
 * <p>它把短期摘要作为可审计记录保存到 SQLite，ContextWindowRuntime 只通过领域 record 读取。</p>
 */
@Repository
public class SQLiteContextSummaryRepository implements ContextSummaryRepository {

    /** 摘要表 mapper，负责 `bq_context_summaries` 单表读写。 */
    private final ContextSummaryMapper mapper;

    /**
     * 创建 SQLite 摘要仓库。
     *
     * @param mapper MyBatis-Plus mapper
     */
    public SQLiteContextSummaryRepository(ContextSummaryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ContextSummaryRecord save(ContextSummaryRecord record) {
        mapper.insert(toEntity(record));
        return record;
    }

    @Override
    public Optional<ContextSummaryRecord> findBySummaryId(String summaryId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ContextSummaryEntity>lambdaQuery()
                        .eq(ContextSummaryEntity::getSummaryId, summaryId)))
                .map(this::toRecord);
    }

    private ContextSummaryRecord toRecord(ContextSummaryEntity entity) {
        return new ContextSummaryRecord(
                entity.getSummaryId(),
                entity.getThreadId(),
                entity.getSourceItemRange(),
                entity.getSourceStartItemId(),
                entity.getSourceEndItemId(),
                entity.getSummary(),
                entity.getProviderId(),
                entity.getModel(),
                entity.getEstimatedTokens() == null ? 0 : entity.getEstimatedTokens(),
                PersistenceTime.read(entity.getCreatedAt()));
    }

    private ContextSummaryEntity toEntity(ContextSummaryRecord record) {
        ContextSummaryEntity entity = new ContextSummaryEntity();
        entity.setSummaryId(record.summaryId());
        entity.setThreadId(record.threadId());
        entity.setSourceItemRange(record.sourceItemRange());
        entity.setSourceStartItemId(record.sourceStartItemId());
        entity.setSourceEndItemId(record.sourceEndItemId());
        entity.setSummary(record.summary());
        entity.setProviderId(record.providerId());
        entity.setModel(record.model());
        entity.setEstimatedTokens(record.estimatedTokens());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        return entity;
    }
}
