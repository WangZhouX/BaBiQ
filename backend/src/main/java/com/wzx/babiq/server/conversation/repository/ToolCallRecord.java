package com.wzx.babiq.server.conversation.repository;

import java.time.Instant;

/**
 * 单次工具调用的领域记录。
 *
 * <p>它比聊天 item 更接近“审计日志”：无论工具结果是否展示给用户，都要保存工具名、参数、
 * 最终状态和错误摘要，供 P2-4 运行详情与后续可观测统计读取。</p>
 *
 * @param toolCallId SAA 工具调用 id，同一 turn 内唯一
 * @param threadId 工具调用所属会话
 * @param turnId 工具调用所属运行回合
 * @param toolName 工具名，例如 read_file、write_file、exec_shell
 * @param argsJson 工具原始参数 JSON，展示时必须按不可信输入处理
 * @param status 工具状态，running、completed、failed 或 denied
 * @param resultPreview 工具结果预览，最多保存短文本，避免 SQLite 被大输出撑爆
 * @param errorMessage 错误或拒绝原因；成功时为空
 * @param startedAt 工具开始时间
 * @param completedAt 工具完成时间；运行中为空
 */
public record ToolCallRecord(
        String toolCallId,
        String threadId,
        String turnId,
        String toolName,
        String argsJson,
        String status,
        String resultPreview,
        String errorMessage,
        String agentName,
        String parentAgentName,
        String delegationId,
        Instant startedAt,
        Instant completedAt
) {
}
