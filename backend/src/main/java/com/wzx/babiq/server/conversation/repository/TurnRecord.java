package com.wzx.babiq.server.conversation.repository;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;

import java.time.Instant;

/**
 * Turn 持久化边界使用的领域记录。
 *
 * <p>它刻意不暴露 MyBatis-Plus Entity，让对话运行代码只关心业务字段；数据库字段名、下划线映射和
 * SQLite 时间格式都由 persistence service 负责转换。</p>
 *
 * @param turnId 协议层 turnId
 * @param threadId 所属 threadId
 * @param status turn 状态
 * @param inputText 用户输入文本
 * @param cwd 本轮工作目录快照
 * @param providerId 本轮 Provider 标识快照
 * @param model 本轮模型名快照
 * @param sandboxMode 本轮沙箱模式快照
 * @param approvalPolicy 本轮审批策略快照
 * @param startedAt 开始时间
 * @param completedAt 完成时间；运行中为空
 * @param failureReason 失败原因；未失败为空
 */
public record TurnRecord(
        String turnId,
        String threadId,
        String status,
        String inputText,
        String cwd,
        String providerId,
        String model,
        String sandboxMode,
        String approvalPolicy,
        Instant startedAt,
        Instant completedAt,
        String failureReason,
        BusinessIdentityScope businessIdentityScope
) {
    public TurnRecord(String turnId, String threadId, String status, String inputText, String cwd,
                      String providerId, String model, String sandboxMode, String approvalPolicy,
                      Instant startedAt, Instant completedAt, String failureReason) {
        this(turnId, threadId, status, inputText, cwd, providerId, model, sandboxMode, approvalPolicy,
                startedAt, completedAt, failureReason, BusinessIdentityScope.UNSCOPED);
    }

    public TurnRecord {
        businessIdentityScope = businessIdentityScope == null
                ? BusinessIdentityScope.UNSCOPED : businessIdentityScope;
    }

    /**
     * 创建一个刚开始运行的 turn 记录。
     *
     * @return completedAt 和 failureReason 为空的运行中记录
     */
    public static TurnRecord started(
            String turnId,
            String threadId,
            String status,
            String inputText,
            String cwd,
            String providerId,
            String model,
            String sandboxMode,
            String approvalPolicy,
            Instant startedAt) {
        return new TurnRecord(turnId, threadId, status, inputText, cwd, providerId, model,
                sandboxMode, approvalPolicy, startedAt, null, null, BusinessIdentityScope.UNSCOPED);
    }

    public static TurnRecord started(
            String turnId, String threadId, String status, String inputText, String cwd,
            String providerId, String model, String sandboxMode, String approvalPolicy,
            Instant startedAt, BusinessIdentityScope scope) {
        return new TurnRecord(turnId, threadId, status, inputText, cwd, providerId, model,
                sandboxMode, approvalPolicy, startedAt, null, null, scope);
    }
}
