package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ToolCallRecord;
import com.wzx.babiq.server.persistence.entity.ToolCallEntity;
import com.wzx.babiq.server.persistence.mapper.ToolCallMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 工具调用持久化服务。
 *
 * <p>ToolObservationInterceptor 只负责拦截 SAA 工具链路，不应该直接拼数据库条件；
 * 本服务把“开始记录、完成更新、按 turn 查询”集中起来，后续换表结构时不会影响拦截器。</p>
 */
@Service
public class ToolCallPersistenceService {

    /** 工具结果预览最大长度，防止大文件或命令输出撑爆运行记录面板。 */
    private static final int PREVIEW_LIMIT = 1_000;

    /** 工具调用表 mapper。 */
    private final ToolCallMapper toolCallMapper;

    /**
     * 创建工具调用持久化服务。
     *
     * @param toolCallMapper 工具调用 mapper
     */
    public ToolCallPersistenceService(ToolCallMapper toolCallMapper) {
        this.toolCallMapper = toolCallMapper;
    }

    /**
     * 记录工具调用开始。
     *
     * @param toolCallId SAA 工具调用 id
     * @param threadId 所属 thread
     * @param turnId 所属 turn
     * @param toolName 工具名
     * @param argsJson 工具参数 JSON
     * @param startedAt 开始时间
     */
    @Transactional
    public void recordStarted(String toolCallId, String threadId, String turnId,
                              String toolName, String argsJson, Instant startedAt) {
        recordStarted(toolCallId, threadId, turnId, toolName, argsJson,
                "babiq_agent", null, null, startedAt);
    }

    /**
     * 记录带 Agent 归属信息的工具调用开始。
     *
     * @param toolCallId SAA 工具调用 id
     * @param threadId 所属 thread
     * @param turnId 所属 turn
     * @param toolName 工具名
     * @param argsJson 工具参数 JSON
     * @param agentName 实际执行该工具的 Agent 名称；主 Agent 默认为 babiq_agent
     * @param parentAgentName 委派来源 Agent；主 Agent 直接调用时为空
     * @param delegationId 子 Agent 委派 id；非委派调用时为空
     * @param startedAt 开始时间
     */
    @Transactional
    public void recordStarted(String toolCallId, String threadId, String turnId,
                              String toolName, String argsJson,
                              String agentName, String parentAgentName,
                              String delegationId, Instant startedAt) {
        ToolCallEntity existing = findEntity(toolCallId);
        ToolCallEntity entity = new ToolCallEntity();
        entity.setToolCallId(toolCallId);
        entity.setThreadId(threadId);
        entity.setTurnId(turnId);
        entity.setToolName(toolName);
        entity.setArgsJson(argsJson == null ? "{}" : argsJson);
        entity.setAgentName(agentName == null || agentName.isBlank() ? "babiq_agent" : agentName);
        entity.setParentAgentName(parentAgentName);
        entity.setDelegationId(delegationId);
        entity.setStatus("running");
        entity.setStartedAt(PersistenceTime.write(startedAt));
        if (existing == null) {
            toolCallMapper.insert(entity);
            return;
        }
        entity.setId(existing.getId());
        entity.setCompletedAt(existing.getCompletedAt());
        toolCallMapper.updateById(entity);
    }

    /**
     * 记录工具调用完成或失败。
     *
     * @param toolCallId SAA 工具调用 id
     * @param status completed、failed 或 denied
     * @param resultPreview 成功结果短预览
     * @param errorMessage 错误或拒绝原因
     * @param completedAt 完成时间
     */
    @Transactional
    public void recordFinished(String toolCallId, String status, String resultPreview,
                               String errorMessage, Instant completedAt) {
        ToolCallEntity existing = findEntity(toolCallId);
        if (existing == null) {
            return;
        }
        existing.setStatus(status);
        existing.setResultPreview(truncate(resultPreview));
        existing.setErrorMessage(truncate(errorMessage));
        existing.setCompletedAt(PersistenceTime.write(completedAt));
        toolCallMapper.updateById(existing);
    }

    /**
     * 按 turn 查询工具调用记录。
     *
     * @param turnId 运行回合 id
     * @return 按开始时间正序排列的工具调用记录
     */
    public List<ToolCallRecord> listByTurnId(String turnId) {
        return toolCallMapper.selectList(Wrappers.<ToolCallEntity>lambdaQuery()
                        .eq(ToolCallEntity::getTurnId, turnId)
                        .orderByAsc(ToolCallEntity::getStartedAt))
                .stream()
                .map(this::toRecord)
                .sorted(Comparator.comparing(ToolCallRecord::startedAt))
                .toList();
    }

    private ToolCallEntity findEntity(String toolCallId) {
        return toolCallMapper.selectOne(Wrappers.<ToolCallEntity>lambdaQuery()
                .eq(ToolCallEntity::getToolCallId, toolCallId));
    }

    private ToolCallRecord toRecord(ToolCallEntity entity) {
        return new ToolCallRecord(
                entity.getToolCallId(),
                entity.getThreadId(),
                entity.getTurnId(),
                entity.getToolName(),
                entity.getArgsJson(),
                entity.getStatus(),
                entity.getResultPreview(),
                entity.getErrorMessage(),
                entity.getAgentName(),
                entity.getParentAgentName(),
                entity.getDelegationId(),
                PersistenceTime.read(entity.getStartedAt()),
                PersistenceTime.read(entity.getCompletedAt()));
    }

    private String truncate(String value) {
        if (value == null || value.length() <= PREVIEW_LIMIT) {
            return value;
        }
        return value.substring(0, PREVIEW_LIMIT) + "...";
    }
}
