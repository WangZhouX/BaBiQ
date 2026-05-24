package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MCP 工具调用 item。
 *
 * <p>P2-6 后 MCP 工具会进入 BaBiQ 工具链路。当前真实审计仍由 `bq_tool_calls`
 * 和 turnSummary 的 toolCalls 承担，本 item 用于未来把 MCP 调用以专门卡片推送给桌面端。</p>
 *
 * @param id item 标识
 * @param type 固定为 mcpToolCall
 * @param serverId MCP server 标识
 * @param toolName MCP 原始工具名
 * @param status 调用状态，例如 running、completed、failed 或 denied
 * @param durationMs 调用耗时毫秒，运行中为空
 * @param error 错误或拒绝原因，成功时为空
 */
public record McpToolCallItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        String serverId,
        String toolName,
        String status,
        Long durationMs,
        String error
) implements ThreadItem {

    /**
     * 创建运行中的 MCP 工具调用 item。
     *
     * @param id item 标识
     */
    public McpToolCallItem(String id) {
        this(id, "mcpToolCall", null, null, "running", null, null);
    }
}
