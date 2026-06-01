package com.wzx.babiq.server.agent.flow;

/**
 * 流程运行持久化记录。
 *
 * <p>该 record 是领域层与 SQLite adapter 之间的边界对象，避免上层直接依赖
 * MyBatis-Plus Entity。</p>
 */
public record OrchestrationRecord(
        String orchestrationId,
        String threadId,
        String turnId,
        String title,
        String topology,
        String status,
        String cwd,
        String sandboxMode,
        boolean approved,
        boolean frozen,
        String summary,
        String errorMessage
) {
}
