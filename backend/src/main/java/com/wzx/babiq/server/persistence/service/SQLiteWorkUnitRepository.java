package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.WorkUnitEntity;
import com.wzx.babiq.server.persistence.entity.WorkUnitGoalEntity;
import com.wzx.babiq.server.persistence.mapper.WorkUnitGoalMapper;
import com.wzx.babiq.server.persistence.mapper.WorkUnitMapper;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 版工作容器仓储。
 *
 * <p>该 adapter 只负责 WorkUnit/Goal 与 MyBatis-Plus Entity 的映射；是否复用容器、是否允许移除等
 * 业务规则由上层 WorkUnitService 判断。</p>
 */
@Repository
public class SQLiteWorkUnitRepository implements WorkUnitRepository {

    private final WorkUnitMapper workUnitMapper;
    private final WorkUnitGoalMapper goalMapper;

    public SQLiteWorkUnitRepository(WorkUnitMapper workUnitMapper, WorkUnitGoalMapper goalMapper) {
        this.workUnitMapper = workUnitMapper;
        this.goalMapper = goalMapper;
    }

    @Override
    public Optional<WorkUnit> findVisibleByName(String threadId, String kind, String normalizedName) {
        return Optional.ofNullable(workUnitMapper.selectOne(Wrappers.<WorkUnitEntity>lambdaQuery()
                        .eq(WorkUnitEntity::getThreadId, threadId)
                        .eq(WorkUnitEntity::getKind, kind)
                        .eq(WorkUnitEntity::getNormalizedName, normalizedName)
                        .eq(WorkUnitEntity::getRemoved, 0)
                        .orderByDesc(WorkUnitEntity::getUpdatedAt)
                        .last("LIMIT 1")))
                .map(this::toRecord);
    }

    @Override
    public Optional<WorkUnit> findById(String workUnitId) {
        return Optional.ofNullable(workUnitMapper.selectOne(Wrappers.<WorkUnitEntity>lambdaQuery()
                        .eq(WorkUnitEntity::getWorkUnitId, workUnitId)))
                .map(this::toRecord);
    }

    @Override
    public Optional<WorkUnitGoal> findGoalById(String goalId) {
        return Optional.ofNullable(goalMapper.selectOne(Wrappers.<WorkUnitGoalEntity>lambdaQuery()
                        .eq(WorkUnitGoalEntity::getGoalId, goalId)))
                .map(this::toRecord);
    }

    @Override
    @Transactional
    public WorkUnit save(WorkUnit workUnit) {
        WorkUnitEntity existing = workUnitMapper.selectOne(Wrappers.<WorkUnitEntity>lambdaQuery()
                .eq(WorkUnitEntity::getWorkUnitId, workUnit.workUnitId()));
        WorkUnitEntity entity = toEntity(workUnit);
        if (existing == null) {
            workUnitMapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            entity.setCreatedAt(existing.getCreatedAt());
            workUnitMapper.updateById(entity);
        }
        return findById(workUnit.workUnitId()).orElse(workUnit);
    }

    @Override
    @Transactional
    public WorkUnitGoal saveGoal(WorkUnitGoal goal) {
        WorkUnitGoalEntity existing = goalMapper.selectOne(Wrappers.<WorkUnitGoalEntity>lambdaQuery()
                .eq(WorkUnitGoalEntity::getGoalId, goal.goalId()));
        WorkUnitGoalEntity entity = toEntity(goal);
        if (existing == null) {
            goalMapper.insert(entity);
        } else {
            entity.setId(existing.getId());
            entity.setCreatedAt(existing.getCreatedAt());
            goalMapper.updateById(entity);
        }
        return findGoalById(goal.goalId()).orElse(goal);
    }

    @Override
    public List<WorkUnit> listVisible(String threadId) {
        return workUnitMapper.selectList(Wrappers.<WorkUnitEntity>lambdaQuery()
                        .eq(WorkUnitEntity::getThreadId, threadId)
                        .eq(WorkUnitEntity::getRemoved, 0)
                        .orderByDesc(WorkUnitEntity::getUpdatedAt))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<WorkUnitGoal> listGoals(String workUnitId) {
        return goalMapper.selectList(Wrappers.<WorkUnitGoalEntity>lambdaQuery()
                        .eq(WorkUnitGoalEntity::getWorkUnitId, workUnitId)
                        .orderByAsc(WorkUnitGoalEntity::getCreatedAt))
                .stream()
                .map(this::toRecord)
                .toList();
    }

    private WorkUnitEntity toEntity(WorkUnit record) {
        WorkUnitEntity entity = new WorkUnitEntity();
        entity.setWorkUnitId(record.workUnitId());
        entity.setThreadId(record.threadId());
        entity.setKind(record.kind());
        entity.setName(record.name());
        entity.setNormalizedName(record.normalizedName());
        entity.setStatus(record.status());
        entity.setCurrentGoalId(record.currentGoalId());
        entity.setCwd(record.cwd());
        entity.setSandboxMode(record.sandboxMode());
        entity.setRemoved(record.removed() ? 1 : 0);
        entity.setRemovedAt(PersistenceTime.write(record.removedAt()));
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        return entity;
    }

    private WorkUnitGoalEntity toEntity(WorkUnitGoal record) {
        WorkUnitGoalEntity entity = new WorkUnitGoalEntity();
        entity.setGoalId(record.goalId());
        entity.setWorkUnitId(record.workUnitId());
        entity.setThreadId(record.threadId());
        entity.setGoalText(record.goalText());
        entity.setStatus(record.status());
        entity.setRunRefType(record.runRefType());
        entity.setRunRefId(record.runRefId());
        entity.setSummary(record.summary());
        entity.setErrorMessage(record.errorMessage());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setStartedAt(PersistenceTime.write(record.startedAt()));
        entity.setCompletedAt(PersistenceTime.write(record.completedAt()));
        return entity;
    }

    private WorkUnit toRecord(WorkUnitEntity entity) {
        return new WorkUnit(
                entity.getWorkUnitId(),
                entity.getThreadId(),
                entity.getKind(),
                entity.getName(),
                entity.getNormalizedName(),
                entity.getStatus(),
                entity.getCurrentGoalId(),
                entity.getCwd(),
                entity.getSandboxMode(),
                entity.getRemoved() != null && entity.getRemoved() == 1,
                PersistenceTime.read(entity.getRemovedAt()),
                PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getUpdatedAt()));
    }

    private WorkUnitGoal toRecord(WorkUnitGoalEntity entity) {
        return new WorkUnitGoal(
                entity.getGoalId(),
                entity.getWorkUnitId(),
                entity.getThreadId(),
                entity.getGoalText(),
                entity.getStatus(),
                entity.getRunRefType(),
                entity.getRunRefId(),
                entity.getSummary(),
                entity.getErrorMessage(),
                PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getStartedAt()),
                PersistenceTime.read(entity.getCompletedAt()));
    }
}
