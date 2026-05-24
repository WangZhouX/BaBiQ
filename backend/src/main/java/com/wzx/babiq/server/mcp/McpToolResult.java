package com.wzx.babiq.server.mcp;

/**
 * MCP 工具调用结果。
 *
 * @param success true 表示 MCP server 返回成功结果
 * @param output 成功输出，进入后续 spotlighting 链路前仍然是不可信内容
 * @param error 失败原因；success 为 true 时为空
 */
public record McpToolResult(boolean success, String output, String error) {

    /**
     * 创建成功结果。
     */
    public static McpToolResult success(String output) {
        return new McpToolResult(true, output == null ? "" : output, null);
    }

    /**
     * 创建失败结果。
     */
    public static McpToolResult failure(String error) {
        return new McpToolResult(false, null, error == null || error.isBlank() ? "MCP tool failed" : error);
    }
}
