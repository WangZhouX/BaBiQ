package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.conversation.repository.TurnSummaryRecord;
import com.wzx.babiq.server.persistence.entity.ItemEntity;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.entity.TurnSummaryEntity;
import com.wzx.babiq.server.persistence.mapper.ItemMapper;
import com.wzx.babiq.server.persistence.mapper.ThreadMapper;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import com.wzx.babiq.server.persistence.mapper.TurnSummaryMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 基于 SQLite + MyBatis-Plus 的对话仓库实现。
 *
 * <p>该类是领域层和数据库之间的适配器：对外暴露 `ConversationRepository` 的业务方法，对内使用
 * Mapper 读写表结构。这样 P2-2 接历史列表时可以只依赖仓库接口，不把 SQL 条件散落到 handler 里。</p>
 */
@Repository
public class SQLiteConversationRepository implements ConversationRepository {

    /** 会话线程表 mapper，负责 `bq_threads` 单表 CRUD。 */
    private final ThreadMapper threadMapper;
    /** item 表 mapper，负责 `bq_items` 单表 CRUD。 */
    private final ItemMapper itemMapper;
    /** turn 表 mapper，负责读取最近 turn 状态，避免列表层重复拼 SQL。 */
    private final TurnMapper turnMapper;
    /** turn 摘要表 mapper，负责 `bq_turn_summaries` 单表 CRUD。 */
    private final TurnSummaryMapper turnSummaryMapper;

    /**
     * 创建 SQLite 对话仓库。
     *
     * @param threadMapper thread 单表 mapper
     * @param itemMapper item 单表 mapper
     * @param turnMapper turn 单表 mapper
     * @param turnSummaryMapper turnSummary 单表 mapper
     */
    public SQLiteConversationRepository(
            ThreadMapper threadMapper,
            ItemMapper itemMapper,
            TurnMapper turnMapper,
            TurnSummaryMapper turnSummaryMapper) {
        this.threadMapper = threadMapper;
        this.itemMapper = itemMapper;
        this.turnMapper = turnMapper;
        this.turnSummaryMapper = turnSummaryMapper;
    }

    @Override
    @Transactional
    public ThreadEntity createThread(
            String threadId,
            String title,
            String cwd,
            String providerId,
            String model,
            String sandboxMode,
            String approvalPolicy,
            Instant now) {
        ThreadEntity entity = ThreadEntity.active(threadId, title, cwd, providerId, model,
                sandboxMode, approvalPolicy, now);
        threadMapper.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public ThreadEntity createThread(String threadId, String title, String cwd, String providerId,
                                     String model, String sandboxMode, String approvalPolicy,
                                     Instant now, BusinessIdentityScope scope) {
        ThreadEntity entity = ThreadEntity.active(threadId, title, cwd, providerId, model,
                sandboxMode, approvalPolicy, now);
        applyScope(entity, scope);
        threadMapper.insert(entity);
        return entity;
    }

    @Override
    public Optional<ThreadEntity> findThread(String threadId) {
        return Optional.ofNullable(threadMapper.selectOne(scopedThreadQuery(BusinessIdentityScope.UNSCOPED)
                .eq(ThreadEntity::getThreadId, threadId)));
    }

    @Override
    public Optional<ThreadEntity> findThread(String threadId, BusinessIdentityScope scope) {
        return Optional.ofNullable(threadMapper.selectOne(scopedThreadQuery(scope)
                .eq(ThreadEntity::getThreadId, threadId)));
    }

    @Override
    public List<ThreadEntity> listRecentThreads(String cwd, boolean includeArchived, int limit) {
        int sanitizedLimit = Math.max(1, limit);
        var query = scopedThreadQuery(BusinessIdentityScope.UNSCOPED)
                .orderByDesc(ThreadEntity::getUpdatedAt)
                .last("LIMIT " + sanitizedLimit);
        if (cwd != null && !cwd.isBlank()) {
            query.eq(ThreadEntity::getCwd, cwd);
        }
        if (!includeArchived) {
            query.isNull(ThreadEntity::getArchivedAt);
        }
        return threadMapper.selectList(query);
    }

    @Override
    public List<ThreadEntity> listRecentThreads(
            String cwd, boolean includeArchived, int limit, BusinessIdentityScope scope) {
        int sanitizedLimit = Math.max(1, limit);
        var query = scopedThreadQuery(scope).orderByDesc(ThreadEntity::getUpdatedAt)
                .last("LIMIT " + sanitizedLimit);
        if (cwd != null && !cwd.isBlank()) query.eq(ThreadEntity::getCwd, cwd);
        if (!includeArchived) query.isNull(ThreadEntity::getArchivedAt);
        return threadMapper.selectList(query);
    }

    @Override
    @Transactional
    public boolean archiveThread(String threadId, Instant archivedAt, BusinessIdentityScope scope) {
        ThreadEntity existing = threadMapper.selectOne(scopedThreadQuery(scope)
                .eq(ThreadEntity::getThreadId, threadId));
        if (existing == null) return false;
        String timestamp = PersistenceTime.write(archivedAt);
        existing.setStatus("archived");
        existing.setArchivedAt(timestamp);
        existing.setUpdatedAt(timestamp);
        threadMapper.updateById(existing);
        return true;
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ThreadEntity> scopedThreadQuery(
            BusinessIdentityScope scope) {
        var query = Wrappers.<ThreadEntity>lambdaQuery();
        if (scope == null || !scope.scoped()) return query.isNull(ThreadEntity::getDesktopInstanceId);
        return query.eq(ThreadEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(ThreadEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(ThreadEntity::getAuthSessionId, scope.authSessionId())
                .eq(ThreadEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(ThreadEntity::getUserId, scope.userId())
                .eq(ThreadEntity::getTenantId, scope.tenantId())
                .eq(ThreadEntity::getPlatformId, scope.platformId());
    }

    private static void applyScope(ThreadEntity entity, BusinessIdentityScope scope) {
        if (scope == null || !scope.scoped()) return;
        entity.setDesktopInstanceId(scope.desktopInstanceId());
        entity.setDesktopSessionId(scope.desktopSessionId());
        entity.setAuthSessionId(scope.authSessionId());
        entity.setIdentityEpoch(scope.identityEpoch());
        entity.setUserId(scope.userId());
        entity.setTenantId(scope.tenantId());
        entity.setPlatformId(scope.platformId());
    }

    @Override
    @Transactional
    public void archiveThread(String threadId, Instant archivedAt) {
        String timestamp = PersistenceTime.write(archivedAt);
        ThreadEntity existing = threadMapper.selectOne(Wrappers.<ThreadEntity>lambdaQuery()
                .isNull(ThreadEntity::getDesktopInstanceId)
                .eq(ThreadEntity::getThreadId, threadId));
        if (existing == null) {
            return;
        }
        existing.setStatus("archived");
        existing.setArchivedAt(timestamp);
        existing.setUpdatedAt(timestamp);
        threadMapper.updateById(existing);
    }

    @Override
    @Transactional
    public void saveItem(ItemRecord record) {
        ItemEntity existing = itemMapper.selectOne(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getItemId, record.itemId()));
        int sequenceNo = existing == null ? nextSequenceNo(record.threadId()) : existing.getSequenceNo();
        ItemEntity entity = toEntity(record, sequenceNo);
        if (existing == null) {
            itemMapper.insert(entity);
            touchThread(record.threadId(), record.updatedAt());
            return;
        }
        entity.setId(existing.getId());
        itemMapper.updateById(entity);
        touchThread(record.threadId(), record.updatedAt());
    }

    @Override
    public Optional<ItemRecord> findItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(itemMapper.selectOne(Wrappers.<ItemEntity>lambdaQuery()
                        .eq(ItemEntity::getItemId, itemId)))
                .map(this::toRecord);
    }

    @Override
    @Transactional
    public Optional<ItemRecord> markItemRemoved(String itemId, Instant removedAt) {
        if (itemId == null || itemId.isBlank()) {
            return Optional.empty();
        }
        ItemEntity existing = itemMapper.selectOne(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getItemId, itemId));
        if (existing == null) {
            return Optional.empty();
        }
        existing.setStatus("removed");
        existing.setUpdatedAt(PersistenceTime.write(removedAt));
        itemMapper.updateById(existing);
        touchThread(existing.getThreadId(), removedAt);
        return Optional.of(toRecord(existing));
    }

    @Override
    public List<ItemRecord> listItems(String threadId, int limit) {
        return listItems(threadId, limit, null);
    }

    @Override
    public List<ItemRecord> listItems(String threadId, int limit, String beforeItemId) {
        return listItems(threadId, limit, beforeItemId, BusinessIdentityScope.UNSCOPED);
    }

    @Override
    public List<ItemRecord> listItems(
            String threadId, int limit, String beforeItemId, BusinessIdentityScope scope) {
        int sanitizedLimit = Math.max(1, limit);
        Integer beforeSequence = null;
        if (beforeItemId != null && !beforeItemId.isBlank()) {
            Optional<ItemEntity> before = findAuthorizedItemEntity(threadId, beforeItemId, scope);
            if (before.isEmpty()) {
                return List.of();
            }
            beforeSequence = before.get().getSequenceNo();
        }
        return itemMapper.selectAuthorizedThreadItems(
                        threadId, sanitizedLimit, beforeSequence,
                        scoped(scope), desktopInstanceId(scope), desktopSessionId(scope), authSessionId(scope),
                        identityEpoch(scope), userId(scope), tenantId(scope), platformId(scope)).stream()
                .map(this::toRecord)
                .sorted(Comparator.comparingInt(ItemRecord::sequenceNo))
                .toList();
    }

    @Override
    public long countItems(String threadId) {
        return countItems(threadId, BusinessIdentityScope.UNSCOPED);
    }

    @Override
    public long countItems(String threadId, BusinessIdentityScope scope) {
        return itemMapper.countAuthorizedThreadItems(
                threadId, scoped(scope), desktopInstanceId(scope), desktopSessionId(scope), authSessionId(scope),
                identityEpoch(scope), userId(scope), tenantId(scope), platformId(scope));
    }

    @Override
    public Optional<String> findLatestTurnStatus(String threadId) {
        return findLatestTurnStatus(threadId, BusinessIdentityScope.UNSCOPED);
    }

    @Override
    public Optional<String> findLatestTurnStatus(String threadId, BusinessIdentityScope scope) {
        return Optional.ofNullable(turnMapper.selectOne(scopedTurnQuery(scope)
                        .eq(TurnEntity::getThreadId, threadId)
                        .orderByDesc(TurnEntity::getStartedAt)
                        .last("LIMIT 1")))
                .map(TurnEntity::getStatus);
    }

    @Override
    @Transactional
    public void saveTurnSummary(TurnSummaryRecord record) {
        TurnSummaryEntity entity = toEntity(record);
        TurnSummaryEntity existing = turnSummaryMapper.selectOne(Wrappers.<TurnSummaryEntity>lambdaQuery()
                .eq(TurnSummaryEntity::getTurnId, record.turnId()));
        if (existing == null) {
            turnSummaryMapper.insert(entity);
            return;
        }
        entity.setId(existing.getId());
        turnSummaryMapper.updateById(entity);
    }

    @Override
    public Optional<TurnSummaryRecord> findTurnSummary(String turnId) {
        return findTurnSummary(turnId, BusinessIdentityScope.UNSCOPED);
    }

    @Override
    public Optional<TurnSummaryRecord> findTurnSummary(String turnId, BusinessIdentityScope scope) {
        if (turnMapper.selectOne(scopedTurnQuery(scope).eq(TurnEntity::getTurnId, turnId)) == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(turnSummaryMapper.selectOne(Wrappers.<TurnSummaryEntity>lambdaQuery()
                        .eq(TurnSummaryEntity::getTurnId, turnId)))
                .map(this::toRecord);
    }

    private void touchThread(String threadId, Instant updatedAt) {
        ThreadEntity existing = threadMapper.selectOne(Wrappers.<ThreadEntity>lambdaQuery()
                .eq(ThreadEntity::getThreadId, threadId));
        if (existing == null) {
            return;
        }
        existing.setUpdatedAt(PersistenceTime.write(updatedAt));
        threadMapper.updateById(existing);
    }

    private int nextSequenceNo(String threadId) {
        ItemEntity latest = itemMapper.selectOne(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getThreadId, threadId)
                .orderByDesc(ItemEntity::getSequenceNo)
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getSequenceNo() + 1;
    }

    private Optional<ItemEntity> findItemEntity(String threadId, String itemId) {
        return Optional.ofNullable(itemMapper.selectOne(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getThreadId, threadId)
                .eq(ItemEntity::getItemId, itemId)));
    }

    private Optional<ItemEntity> findAuthorizedItemEntity(
            String threadId, String itemId, BusinessIdentityScope scope) {
        return Optional.ofNullable(itemMapper.selectAuthorizedThreadItem(
                threadId, itemId,
                scoped(scope), desktopInstanceId(scope), desktopSessionId(scope), authSessionId(scope),
                identityEpoch(scope), userId(scope), tenantId(scope), platformId(scope)));
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TurnEntity> scopedTurnQuery(
            BusinessIdentityScope scope) {
        var query = Wrappers.<TurnEntity>lambdaQuery();
        if (scope == null || !scope.scoped()) return query.isNull(TurnEntity::getDesktopInstanceId);
        return query.eq(TurnEntity::getDesktopInstanceId, scope.desktopInstanceId())
                .eq(TurnEntity::getDesktopSessionId, scope.desktopSessionId())
                .eq(TurnEntity::getAuthSessionId, scope.authSessionId())
                .eq(TurnEntity::getIdentityEpoch, scope.identityEpoch())
                .eq(TurnEntity::getUserId, scope.userId())
                .eq(TurnEntity::getTenantId, scope.tenantId())
                .eq(TurnEntity::getPlatformId, scope.platformId());
    }

    private static int scoped(BusinessIdentityScope scope) { return scope != null && scope.scoped() ? 1 : 0; }
    private static String desktopInstanceId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.desktopInstanceId() : null; }
    private static String desktopSessionId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.desktopSessionId() : null; }
    private static String authSessionId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.authSessionId() : null; }
    private static Long identityEpoch(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.identityEpoch() : null; }
    private static String userId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.userId() : null; }
    private static String tenantId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.tenantId() : null; }
    private static String platformId(BusinessIdentityScope scope) { return scoped(scope) == 1 ? scope.platformId() : null; }

    private ItemEntity toEntity(ItemRecord record, int sequenceNo) {
        ItemEntity entity = new ItemEntity();
        entity.setItemId(record.itemId());
        entity.setThreadId(record.threadId());
        entity.setTurnId(record.turnId());
        entity.setType(record.type());
        entity.setSequenceNo(sequenceNo);
        entity.setPayloadJson(record.payloadJson());
        entity.setStatus(record.status());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        return entity;
    }

    private ItemRecord toRecord(ItemEntity entity) {
        return new ItemRecord(entity.getItemId(), entity.getThreadId(), entity.getTurnId(),
                entity.getType(), entity.getSequenceNo(), entity.getPayloadJson(), entity.getStatus(),
                PersistenceTime.read(entity.getCreatedAt()), PersistenceTime.read(entity.getUpdatedAt()));
    }

    private TurnSummaryEntity toEntity(TurnSummaryRecord record) {
        TurnSummaryEntity entity = new TurnSummaryEntity();
        entity.setTurnId(record.turnId());
        entity.setPromptTokens(record.promptTokens());
        entity.setCompletionTokens(record.completionTokens());
        entity.setTotalTokens(record.totalTokens());
        entity.setDurationMs(record.durationMs());
        entity.setToolCount(record.toolCount());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        return entity;
    }

    private TurnSummaryRecord toRecord(TurnSummaryEntity entity) {
        return new TurnSummaryRecord(entity.getTurnId(), entity.getPromptTokens(), entity.getCompletionTokens(),
                entity.getTotalTokens(), entity.getDurationMs(), entity.getToolCount(),
                PersistenceTime.read(entity.getCreatedAt()));
    }
}
