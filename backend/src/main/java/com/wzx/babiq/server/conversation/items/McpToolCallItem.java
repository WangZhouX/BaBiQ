package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MCP 工具调用占位 item。
 *
 * <p>P1-1 不接 MCP 工具,但协议类型必须先存在,否则 sealed interface 无法闭合,
 * 桌面端也无法提前识别该类事件。</p>
 *
 * @param id item 标识
 * @param type 固定为 mcpToolCall
 */
public record McpToolCallItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type
) implements ThreadItem {

    /**
     * 创建 MCP 工具调用占位 item。
     *
     * @param id item 标识
     */
    public McpToolCallItem(String id) {
        this(id, "mcpToolCall");
    }
}
