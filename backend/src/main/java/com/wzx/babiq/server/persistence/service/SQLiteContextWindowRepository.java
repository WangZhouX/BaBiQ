package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.context.repository.ContextWindowRecord;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.context.repository.ContextWindowRepository;
import com.wzx.babiq.server.persistence.entity.ContextWindowEntity;
import com.wzx.babiq.server.persistence.mapper.ContextWindowMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
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
        return Optional.ofNullable(mapper.selectOne(scopedQuery(BusinessIdentityScope.UNSCOPED)
                        .eq(ContextWindowEntity::getThreadId, threadId)))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextWindowRecord> findByThreadId(String threadId, BusinessIdentityScope scope) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(scope)
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
        if (!sameScope(existing, record.businessIdentityScope())) {
            throw new IllegalStateException("context window immutable scope conflict");
        }
        entity.setId(existing.getId());
        entity.setCreatedAt(existing.getCreatedAt());
        mapper.updateById(entity);
        return toRecord(entity);
    }

    @Override
    @Transactional
    public boolean compareAndSwapOrdinal(String threadId, int expectedOrdinal, ContextWindowRecord nextRecord) {
        ContextWindowEntity existing = mapper.selectOne(Wrappers.<ContextWindowEntity>lambdaQuery()
                .eq(ContextWindowEntity::getThreadId, threadId));
        ContextWindowEntity entity = toEntity(nextRecord);
        if (existing == null) {
            return insertInitialWindow(expectedOrdinal, entity);
        }
        if (!sameScope(existing, nextRecord.businessIdentityScope())) {
            throw new IllegalStateException("context window immutable scope conflict");
        }
        entity.setId(existing.getId());
        entity.setCreatedAt(existing.getCreatedAt());
        var update = Wrappers.<ContextWindowEntity>lambdaUpdate()
                .eq(ContextWindowEntity::getThreadId, threadId)
                .eq(ContextWindowEntity::getWindowOrdinal, expectedOrdinal);
        applyScope(update, nextRecord.businessIdentityScope());
        int updated = mapper.update(entity, update);
        return updated > 0;
    }

    private boolean insertInitialWindow(int expectedOrdinal, ContextWindowEntity entity) {
        if (expectedOrdinal != 0) {
            return false;
        }
        try {
            return mapper.insert(entity) > 0;
        } catch (DataIntegrityViolationException exception) {
            // 另一个并发流程已经为该 thread 创建了窗口，调用方按 CAS 失败降级即可。
            return false;
        }
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
                PersistenceTime.read(entity.getUpdatedAt()),
                scope(entity));
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
        applyScope(entity, record.businessIdentityScope());
        return entity;
    }

    private static BusinessIdentityScope scope(ContextWindowEntity entity) {
        if (entity.getDesktopInstanceId() == null) return BusinessIdentityScope.UNSCOPED;
        return BusinessIdentityScope.scoped(entity.getDesktopInstanceId(), entity.getDesktopSessionId(),
                entity.getAuthSessionId(), entity.getIdentityEpoch(), entity.getUserId(),
                entity.getTenantId(), entity.getPlatformId());
    }

    private static void applyScope(ContextWindowEntity entity, BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) return;
        entity.setDesktopInstanceId(scope.desktopInstanceId()); entity.setDesktopSessionId(scope.desktopSessionId());
        entity.setAuthSessionId(scope.authSessionId()); entity.setIdentityEpoch(scope.identityEpoch());
        entity.setUserId(scope.userId()); entity.setTenantId(scope.tenantId()); entity.setPlatformId(scope.platformId());
    }

    private static void applyScope(
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ContextWindowEntity> update,
            BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) {
            update.isNull(ContextWindowEntity::getDesktopInstanceId);
            return;
        }
        update.eq(ContextWindowEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(ContextWindowEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(ContextWindowEntity::getAuthSessionId, scope.authSessionId())
                .eq(ContextWindowEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(ContextWindowEntity::getUserId, scope.userId())
                .eq(ContextWindowEntity::getTenantId, scope.tenantId())
                .eq(ContextWindowEntity::getPlatformId, scope.platformId());
    }

    private static boolean sameScope(ContextWindowEntity entity, BusinessIdentityScope scope) {
        BusinessIdentityScope expected = scope == null ? BusinessIdentityScope.UNSCOPED : scope;
        return Objects.equals(scope(entity), expected);
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContextWindowEntity>
    scopedQuery(BusinessIdentityScope scope) {
        var query = Wrappers.<ContextWindowEntity>lambdaQuery();
        if (scope == null || !scope.scoped()) return query.isNull(ContextWindowEntity::getDesktopInstanceId);
        return query.eq(ContextWindowEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(ContextWindowEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(ContextWindowEntity::getAuthSessionId, scope.authSessionId())
                .eq(ContextWindowEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(ContextWindowEntity::getUserId, scope.userId())
                .eq(ContextWindowEntity::getTenantId, scope.tenantId())
                .eq(ContextWindowEntity::getPlatformId, scope.platformId());
    }
}
