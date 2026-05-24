package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
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
    public Optional<ThreadEntity> findThread(String threadId) {
        return Optional.ofNullable(threadMapper.selectOne(Wrappers.<ThreadEntity>lambdaQuery()
                .eq(ThreadEntity::getThreadId, threadId)));
    }

    @Override
    public List<ThreadEntity> listRecentThreads(String cwd, boolean includeArchived, int limit) {
        int sanitizedLimit = Math.max(1, limit);
        var query = Wrappers.<ThreadEntity>lambdaQuery()
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
    @Transactional
    public void archiveThread(String threadId, Instant archivedAt) {
        String timestamp = PersistenceTime.write(archivedAt);
        ThreadEntity existing = threadMapper.selectOne(Wrappers.<ThreadEntity>lambdaQuery()
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
    public List<ItemRecord> listItems(String threadId, int limit) {
        return listItems(threadId, limit, null);
    }

    @Override
    public List<ItemRecord> listItems(String threadId, int limit, String beforeItemId) {
        int sanitizedLimit = Math.max(1, limit);
        var query = Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getThreadId, threadId)
                .orderByDesc(ItemEntity::getSequenceNo)
                .last("LIMIT " + sanitizedLimit);
        if (beforeItemId != null && !beforeItemId.isBlank()) {
            Optional<ItemEntity> before = findItemEntity(threadId, beforeItemId);
            if (before.isEmpty()) {
                return List.of();
            }
            query.lt(ItemEntity::getSequenceNo, before.get().getSequenceNo());
        }
        return itemMapper.selectList(query).stream()
                .map(this::toRecord)
                .sorted(Comparator.comparingInt(ItemRecord::sequenceNo))
                .toList();
    }

    @Override
    public long countItems(String threadId) {
        Long count = itemMapper.selectCount(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getThreadId, threadId));
        return count == null ? 0L : count;
    }

    @Override
    public Optional<String> findLatestTurnStatus(String threadId) {
        return Optional.ofNullable(turnMapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
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
