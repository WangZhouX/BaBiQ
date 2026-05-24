package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.persistence.entity.ApprovalEntity;
import com.wzx.babiq.server.persistence.mapper.ApprovalMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 审批请求持久化服务。
 *
 * <p>P2-4 需要把 approval/request 和 approval/respond 都落库：如果进程重启，
 * pending 审批会被恢复服务标记为 expired；如果用户已决策，则运行记录可以展示当时的选择。</p>
 */
@Service
public class ApprovalPersistenceService {

    /** 审批表 mapper。 */
    private final ApprovalMapper approvalMapper;

    /**
     * 创建审批持久化服务。
     *
     * @param approvalMapper 审批 mapper
     */
    public ApprovalPersistenceService(ApprovalMapper approvalMapper) {
        this.approvalMapper = approvalMapper;
    }

    /**
     * 保存一个待处理审批请求。
     *
     * @param approvalId 协议层 approval/item id
     * @param threadId 所属 thread
     * @param turnId 所属 turn
     * @param toolName 待执行工具名
     * @param argsJson 工具参数 JSON
     * @param createdAt 创建时间
     */
    @Transactional
    public void savePending(String approvalId, String threadId, String turnId,
                            String toolName, String argsJson, Instant createdAt) {
        ApprovalEntity existing = approvalMapper.selectOne(Wrappers.<ApprovalEntity>lambdaQuery()
                .eq(ApprovalEntity::getApprovalId, approvalId));
        ApprovalEntity entity = new ApprovalEntity();
        entity.setApprovalId(approvalId);
        entity.setThreadId(threadId);
        entity.setTurnId(turnId);
        entity.setToolName(toolName);
        entity.setArgsJson(argsJson == null ? "{}" : argsJson);
        entity.setStatus("pending");
        entity.setCreatedAt(PersistenceTime.write(createdAt));
        if (existing == null) {
            approvalMapper.insert(entity);
            return;
        }
        entity.setId(existing.getId());
        approvalMapper.updateById(entity);
    }

    /**
     * 把某个 turn 的 pending 审批标记为已决策。
     *
     * @param threadId 所属 thread
     * @param turnId 所属 turn
     * @param decision 用户决策
     * @param scope 决策作用域
     * @param editedArgsJson 用户编辑后的参数 JSON
     * @param resolvedAt 决策时间
     */
    @Transactional
    public void resolvePending(String threadId, String turnId, String decision,
                               String scope, String editedArgsJson, Instant resolvedAt) {
        List<ApprovalEntity> approvals = approvalMapper.selectList(Wrappers.<ApprovalEntity>lambdaQuery()
                .eq(ApprovalEntity::getThreadId, threadId)
                .eq(ApprovalEntity::getTurnId, turnId)
                .eq(ApprovalEntity::getStatus, "pending"));
        for (ApprovalEntity approval : approvals) {
            approval.setDecision(decision);
            approval.setScope(scope);
            approval.setEditedArgsJson(editedArgsJson);
            approval.setStatus("resolved");
            approval.setResolvedAt(PersistenceTime.write(resolvedAt));
            approvalMapper.updateById(approval);
        }
    }

    /**
     * 把指定 turn 集合内的 pending 审批全部过期。
     *
     * @param turnIds 等待审批的 turn id 集合
     * @param resolvedAt 过期时间
     * @return 被更新的审批数量
     */
    @Transactional
    public int expirePendingByTurnIds(List<String> turnIds, Instant resolvedAt) {
        if (turnIds == null || turnIds.isEmpty()) {
            return 0;
        }
        List<ApprovalEntity> approvals = approvalMapper.selectList(Wrappers.<ApprovalEntity>lambdaQuery()
                .in(ApprovalEntity::getTurnId, turnIds)
                .eq(ApprovalEntity::getStatus, "pending"));
        for (ApprovalEntity approval : approvals) {
            approval.setStatus("expired");
            approval.setResolvedAt(PersistenceTime.write(resolvedAt));
            approvalMapper.updateById(approval);
        }
        return approvals.size();
    }

    /**
     * 按 turn 查询审批记录。
     *
     * @param turnId 运行回合 id
     * @return 审批记录列表
     */
    public List<ApprovalEntity> listByTurnId(String turnId) {
        return approvalMapper.selectList(Wrappers.<ApprovalEntity>lambdaQuery()
                .eq(ApprovalEntity::getTurnId, turnId)
                .orderByAsc(ApprovalEntity::getCreatedAt));
    }
}
