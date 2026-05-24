package com.wzx.babiq.server.api.dto;

/**
 * 运行记录列表中的单个 turn 摘要。
 *
 * @param turnId 运行回合 id
 * @param threadId 所属会话 id
 * @param status turn 当前或最终状态
 * @param inputText 用户原始输入
 * @param cwd 本轮工作目录快照
 * @param providerId 本轮 Provider 快照
 * @param model 本轮模型快照
 * @param startedAt 开始时间字符串
 * @param completedAt 完成时间字符串；未完成时为空
 * @param recoveryReason 恢复收口原因；非恢复 turn 为空
 * @param recoveredAt 恢复收口时间；非恢复 turn 为空
 */
public record RunTurnSummaryDto(
        String turnId,
        String threadId,
        String status,
        String inputText,
        String cwd,
        String providerId,
        String model,
        String startedAt,
        String completedAt,
        String recoveryReason,
        String recoveredAt
) {
}
