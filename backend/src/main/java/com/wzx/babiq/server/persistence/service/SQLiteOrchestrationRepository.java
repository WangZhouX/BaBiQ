package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.agent.flow.OrchestrationNodeRecord;
import com.wzx.babiq.server.agent.flow.OrchestrationRecord;
import com.wzx.babiq.server.agent.flow.OrchestrationRepository;
import com.wzx.babiq.server.persistence.entity.OrchestrationEntity;
import com.wzx.babiq.server.persistence.entity.OrchestrationNodeEntity;
import com.wzx.babiq.server.persistence.mapper.OrchestrationMapper;
import com.wzx.babiq.server.persistence.mapper.OrchestrationNodeMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 版流程编排仓储。
 *
 * <p>它把领域 record 映射到 MyBatis-Plus Entity，并负责节点状态的幂等更新。
 * Agent/工具层只依赖 {@link OrchestrationRepository} 端口，不直接感知表结构。</p>
 */
@Repository
public class SQLiteOrchestrationRepository implements OrchestrationRepository {

    /** 流程整体运行表 mapper。 */
    private final OrchestrationMapper orchestrationMapper;
    /** 流程节点表 mapper。 */
    private final OrchestrationNodeMapper nodeMapper;

    /**
     * 创建 SQLite 流程仓储。
     */
    public SQLiteOrchestrationRepository(OrchestrationMapper orchestrationMapper,
                                         OrchestrationNodeMapper nodeMapper) {
        this.orchestrationMapper = orchestrationMapper;
        this.nodeMapper = nodeMapper;
    }

    @Override
    @Transactional
    public void save(OrchestrationRecord record, List<OrchestrationNodeRecord> nodes) {
        String now = PersistenceTime.write(Instant.now());
        OrchestrationEntity existing = findEntity(record.orchestrationId());
        OrchestrationEntity entity = toEntity(record);
        entity.setCreatedAt(existing == null ? now : existing.getCreatedAt());
        entity.setUpdatedAt(now);
        if (existing == null) {
            orchestrationMapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            orchestrationMapper.updateById(entity);
        }
        nodeMapper.delete(Wrappers.<OrchestrationNodeEntity>lambdaQuery()
                .eq(OrchestrationNodeEntity::getOrchestrationId, record.orchestrationId()));
        for (OrchestrationNodeRecord node : nodes == null ? List.<OrchestrationNodeRecord>of() : nodes) {
            OrchestrationNodeEntity nodeEntity = toEntity(node);
            nodeEntity.setCreatedAt(now);
            nodeEntity.setUpdatedAt(now);
            nodeMapper.insert(nodeEntity);
        }
    }

    @Override
    @Transactional
    public void updateNode(String orchestrationId, String nodeId, String status,
                           int toolCallCount, int tokenEstimate, String summary) {
        OrchestrationNodeEntity entity = nodeMapper.selectOne(Wrappers.<OrchestrationNodeEntity>lambdaQuery()
                .eq(OrchestrationNodeEntity::getOrchestrationId, orchestrationId)
                .eq(OrchestrationNodeEntity::getNodeId, nodeId));
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setToolCallCount(toolCallCount);
        entity.setTokenEstimate(tokenEstimate);
        entity.setSummary(summary);
        entity.setUpdatedAt(PersistenceTime.write(Instant.now()));
        nodeMapper.updateById(entity);
    }

    @Override
    public Optional<OrchestrationRecord> findByOrchestrationId(String orchestrationId) {
        return Optional.ofNullable(findEntity(orchestrationId)).map(this::toRecord);
    }

    @Override
    public List<OrchestrationNodeRecord> listNodes(String orchestrationId) {
        return nodeMapper.selectList(Wrappers.<OrchestrationNodeEntity>lambdaQuery()
                        .eq(OrchestrationNodeEntity::getOrchestrationId, orchestrationId)
                        .orderByAsc(OrchestrationNodeEntity::getNodeOrder))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    private OrchestrationEntity findEntity(String orchestrationId) {
        return orchestrationMapper.selectOne(Wrappers.<OrchestrationEntity>lambdaQuery()
                .eq(OrchestrationEntity::getOrchestrationId, orchestrationId));
    }

    private OrchestrationEntity toEntity(OrchestrationRecord record) {
        OrchestrationEntity entity = new OrchestrationEntity();
        entity.setOrchestrationId(record.orchestrationId());
        entity.setThreadId(record.threadId());
        entity.setTurnId(record.turnId());
        entity.setTitle(record.title());
        entity.setTopology(record.topology());
        entity.setStatus(record.status());
        entity.setCwd(record.cwd());
        entity.setSandboxMode(record.sandboxMode());
        entity.setApproved(record.approved() ? 1 : 0);
        entity.setFrozen(record.frozen() ? 1 : 0);
        entity.setStructureJson(record.structureJson());
        entity.setSummary(record.summary());
        entity.setErrorMessage(record.errorMessage());
        return entity;
    }

    private OrchestrationNodeEntity toEntity(OrchestrationNodeRecord record) {
        OrchestrationNodeEntity entity = new OrchestrationNodeEntity();
        entity.setOrchestrationId(record.orchestrationId());
        entity.setNodeId(record.nodeId());
        entity.setName(record.name());
        entity.setDisplayName(record.displayName());
        entity.setMode(record.mode());
        entity.setToolNames(record.toolNames());
        entity.setStatus(record.status());
        entity.setNodeOrder(record.nodeOrder());
        entity.setToolCallCount(record.toolCallCount());
        entity.setTokenEstimate(record.tokenEstimate());
        entity.setSummary(record.summary());
        return entity;
    }

    private OrchestrationRecord toRecord(OrchestrationEntity entity) {
        return new OrchestrationRecord(
                entity.getOrchestrationId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getTitle(),
                entity.getTopology(),
                entity.getStatus(),
                entity.getCwd(),
                entity.getSandboxMode(),
                entity.getApproved() != null && entity.getApproved() == 1,
                entity.getFrozen() != null && entity.getFrozen() == 1,
                entity.getStructureJson(),
                entity.getSummary(),
                entity.getErrorMessage());
    }

    private OrchestrationNodeRecord toRecord(OrchestrationNodeEntity entity) {
        return new OrchestrationNodeRecord(
                entity.getOrchestrationId(),
                entity.getNodeId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getMode(),
                entity.getToolNames(),
                entity.getStatus(),
                entity.getNodeOrder() == null ? 0 : entity.getNodeOrder(),
                entity.getToolCallCount() == null ? 0 : entity.getToolCallCount(),
                entity.getTokenEstimate() == null ? 0 : entity.getTokenEstimate(),
                entity.getSummary());
    }
}
