package com.wzx.babiq.server.observability;

/**
 * 工具调用维度的本地统计。
 *
 * @param toolName 工具名，例如 read_file 或 exec_shell。
 * @param calls 调用次数，来自 `bq_tool_calls`。
 * @param failures 失败或拒绝次数，按 failed/denied 状态计算。
 * @param avgDurationMs 已完成工具调用的平均耗时；运行中没有 completed_at 的记录不参与耗时平均。
 */
public record ToolStats(
        String toolName,
        long calls,
        long failures,
        long avgDurationMs) {
}
