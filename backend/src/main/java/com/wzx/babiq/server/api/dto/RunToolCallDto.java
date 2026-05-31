package com.wzx.babiq.server.api.dto;

/**
 * 运行详情中的工具调用记录。
 *
 * @param toolCallId 工具调用 id
 * @param toolName 工具名
 * @param argsJson 原始参数 JSON
 * @param status 工具状态
 * @param resultPreview 结果短预览
 * @param errorMessage 错误或拒绝原因
 * @param startedAt 开始时间
 * @param completedAt 完成时间
 */
public record RunToolCallDto(
        String toolCallId,
        String toolName,
        String argsJson,
        String agentName,
        String parentAgentName,
        String delegationId,
        String status,
        String resultPreview,
        String errorMessage,
        String startedAt,
        String completedAt
) {
}
