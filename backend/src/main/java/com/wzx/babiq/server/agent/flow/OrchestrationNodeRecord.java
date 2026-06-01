package com.wzx.babiq.server.agent.flow;

/**
 * 流程节点持久化记录。
 *
 * <p>节点记录用于右侧运行详情和后续审计。工具调用的细粒度信息仍保存在
 * {@code bq_tool_calls}，这里仅保存节点聚合状态。</p>
 */
public record OrchestrationNodeRecord(
        String orchestrationId,
        String nodeId,
        String name,
        String displayName,
        String mode,
        String toolNames,
        String status,
        int nodeOrder,
        int toolCallCount,
        int tokenEstimate,
        String summary
) {
}
