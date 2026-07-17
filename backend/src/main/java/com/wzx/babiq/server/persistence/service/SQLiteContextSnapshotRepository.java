package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.context.repository.ContextSnapshotRecord;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.context.repository.ContextSnapshotRepository;
import com.wzx.babiq.server.persistence.entity.ContextSnapshotEntity;
import com.wzx.babiq.server.persistence.mapper.ContextSnapshotMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
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
        if (!sameImmutableMetadata(existing, entity)) {
            throw new IllegalStateException("context snapshot immutable metadata conflict");
        }
    }

    @Override
    public Optional<ContextSnapshotRecord> findBySnapshotId(String snapshotId) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(BusinessIdentityScope.UNSCOPED)
                        .eq(ContextSnapshotEntity::getSnapshotId, snapshotId)))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findBySnapshotId(String snapshotId, BusinessIdentityScope scope) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(scope)
                        .eq(ContextSnapshotEntity::getSnapshotId, snapshotId)))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findLatestByTurnId(String turnId) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(BusinessIdentityScope.UNSCOPED)
                        .eq(ContextSnapshotEntity::getTurnId, turnId)
                        .orderByDesc(ContextSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findLatestByTurnId(String turnId, BusinessIdentityScope scope) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(scope)
                        .eq(ContextSnapshotEntity::getTurnId, turnId)
                        .orderByDesc(ContextSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findLatestByThreadId(String threadId) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(BusinessIdentityScope.UNSCOPED)
                        .eq(ContextSnapshotEntity::getThreadId, threadId)
                        .orderByDesc(ContextSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    public Optional<ContextSnapshotRecord> findLatestByThreadId(String threadId, BusinessIdentityScope scope) {
        return Optional.ofNullable(mapper.selectOne(scopedQuery(scope)
                        .eq(ContextSnapshotEntity::getThreadId, threadId)
                        .orderByDesc(ContextSnapshotEntity::getCreatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    @Transactional
    public void updateActualPromptTokens(String snapshotId, Long actualPromptTokens) {
        updateActualPromptTokens(snapshotId, actualPromptTokens, BusinessIdentityScope.UNSCOPED);
    }

    @Override
    @Transactional
    public void updateActualPromptTokens(
            String snapshotId, Long actualPromptTokens, BusinessIdentityScope scope) {
        ContextSnapshotEntity existing = mapper.selectOne(scopedQuery(scope)
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
                PersistenceTime.read(entity.getCreatedAt()),
                scope(entity));
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
        applyScope(entity, record.businessIdentityScope());
        return entity;
    }

    private static BusinessIdentityScope scope(ContextSnapshotEntity entity) {
        if (entity.getDesktopInstanceId() == null) return BusinessIdentityScope.UNSCOPED;
        return BusinessIdentityScope.scoped(entity.getDesktopInstanceId(), entity.getDesktopSessionId(),
                entity.getAuthSessionId(), entity.getIdentityEpoch(), entity.getUserId(),
                entity.getTenantId(), entity.getPlatformId());
    }

    private static void applyScope(ContextSnapshotEntity entity, BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) return;
        entity.setDesktopInstanceId(scope.desktopInstanceId()); entity.setDesktopSessionId(scope.desktopSessionId());
        entity.setAuthSessionId(scope.authSessionId()); entity.setIdentityEpoch(scope.identityEpoch());
        entity.setUserId(scope.userId()); entity.setTenantId(scope.tenantId()); entity.setPlatformId(scope.platformId());
    }

    private static boolean sameImmutableMetadata(
            ContextSnapshotEntity existing, ContextSnapshotEntity candidate) {
        return Objects.equals(existing.getThreadId(), candidate.getThreadId())
                && Objects.equals(existing.getTurnId(), candidate.getTurnId())
                && Objects.equals(existing.getPhase(), candidate.getPhase())
                && Objects.equals(existing.getProviderId(), candidate.getProviderId())
                && Objects.equals(existing.getModel(), candidate.getModel())
                && Objects.equals(existing.getCwd(), candidate.getCwd())
                && Objects.equals(existing.getWindowOrdinal(), candidate.getWindowOrdinal())
                && Objects.equals(existing.getModelContextWindow(), candidate.getModelContextWindow())
                && Objects.equals(existing.getAutoCompactThreshold(), candidate.getAutoCompactThreshold())
                && Objects.equals(existing.getEstimatedTokens(), candidate.getEstimatedTokens())
                && Objects.equals(existing.getIncludedItemCount(), candidate.getIncludedItemCount())
                && Objects.equals(existing.getExcludedItemCount(), candidate.getExcludedItemCount())
                && Objects.equals(existing.getEnvelopeJson(), candidate.getEnvelopeJson())
                && Objects.equals(existing.getItemsJson(), candidate.getItemsJson())
                && Objects.equals(existing.getCapabilityCatalogJson(), candidate.getCapabilityCatalogJson())
                && Objects.equals(existing.getLongTermMemoryRefsJson(), candidate.getLongTermMemoryRefsJson())
                && Objects.equals(existing.getLongTermMemoryTokenEstimate(), candidate.getLongTermMemoryTokenEstimate())
                && Objects.equals(existing.getInputPreview(), candidate.getInputPreview())
                && Objects.equals(existing.getCreatedAt(), candidate.getCreatedAt())
                && Objects.equals(existing.getDesktopInstanceId(), candidate.getDesktopInstanceId())
                && Objects.equals(existing.getDesktopSessionId(), candidate.getDesktopSessionId())
                && Objects.equals(existing.getAuthSessionId(), candidate.getAuthSessionId())
                && Objects.equals(existing.getIdentityEpoch(), candidate.getIdentityEpoch())
                && Objects.equals(existing.getUserId(), candidate.getUserId())
                && Objects.equals(existing.getTenantId(), candidate.getTenantId())
                && Objects.equals(existing.getPlatformId(), candidate.getPlatformId());
    }

    private static com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContextSnapshotEntity>
    scopedQuery(BusinessIdentityScope scope) {
        var query = Wrappers.<ContextSnapshotEntity>lambdaQuery();
        if (scope == null || !scope.scoped()) return query.isNull(ContextSnapshotEntity::getDesktopInstanceId);
        return query.eq(ContextSnapshotEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(ContextSnapshotEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(ContextSnapshotEntity::getAuthSessionId, scope.authSessionId())
                .eq(ContextSnapshotEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(ContextSnapshotEntity::getUserId, scope.userId())
                .eq(ContextSnapshotEntity::getTenantId, scope.tenantId())
                .eq(ContextSnapshotEntity::getPlatformId, scope.platformId());
    }
}
