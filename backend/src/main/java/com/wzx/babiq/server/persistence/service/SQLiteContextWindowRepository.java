package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.persistence.entity.ContextWindowEntity;
import com.wzx.babiq.server.persistence.mapper.ContextWindowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ContextWindowRepository 的 SQLite 实现。
 *
 * <p>这里封装 upsert 细节，让 Agent/runtime 层只面对 thread 级窗口状态。</p>
 */
@Repository
public class SQLiteContextWindowRepository implements ContextWindowRepository {

    /** 上下文窗口表 mapper，负责 `bq_context_windows` 单表读写。 */
    private final ContextWindowMapper mapper;

    /**
     * 创建 SQLite 上下文窗口仓库。
     *
     * @param mapper MyBatis-Plus mapper
     */
    public SQLiteContextWindowRepository(ContextWindowMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ContextWindowRecord> findByThreadId(String threadId) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ContextWindowEntity>lambdaQuery()
                        .eq(ContextWindowEntity::getThreadId, threadId)))
                .map(this::toRecord);
    }

    @Override
    @Transactional
    public ContextWindowRecord upsert(ContextWindowRecord record) {
        ContextWindowEntity existing = mapper.selectOne(Wrappers.<ContextWindowEntity>lambdaQuery()
                .eq(ContextWindowEntity::getThreadId, record.threadId()));
        ContextWindowEntity entity = toEntity(record);
        if (existing == null) {
            mapper.insert(entity);
            return record;
        }
        entity.setId(existing.getId());
        entity.setCreatedAt(existing.getCreatedAt());
        mapper.updateById(entity);
        return toRecord(entity);
    }

    private ContextWindowRecord toRecord(ContextWindowEntity entity) {
        return new ContextWindowRecord(
                entity.getThreadId(),
                entity.getWindowOrdinal() == null ? 0 : entity.getWindowOrdinal(),
                entity.getActiveSummaryId(),
                entity.getModelContextWindow() == null ? 0 : entity.getModelContextWindow(),
                entity.getAutoCompactThreshold() == null ? 0 : entity.getAutoCompactThreshold(),
                entity.getLastSnapshotId(),
                PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getUpdatedAt()));
    }

    private ContextWindowEntity toEntity(ContextWindowRecord record) {
        ContextWindowEntity entity = new ContextWindowEntity();
        entity.setThreadId(record.threadId());
        entity.setWindowOrdinal(record.windowOrdinal());
        entity.setActiveSummaryId(record.activeSummaryId());
        entity.setModelContextWindow(record.modelContextWindow());
        entity.setAutoCompactThreshold(record.autoCompactThreshold());
        entity.setLastSnapshotId(record.lastSnapshotId());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        return entity;
    }
}
