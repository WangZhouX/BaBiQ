package com.wzx.babiq.server.mcp;

/**
 * MCP 工具描述符。
 *
 * <p>它是 MCP SDK Tool 和 BaBiQ ToolCallback 之间的中间模型。这样 UI、持久化和工具适配器
 * 都不用直接依赖 SDK record，后续 SDK 小版本变化时只需要调整 connector。</p>
 *
 * @param serverId 工具所属 MCP server
 * @param toolName MCP server 原始工具名
 * @param namespacedName BaBiQ 内部工具名，例如 mcp.local-filesystem.read_file
 * @param description 工具描述，展示给模型和设置页
 * @param inputSchema JSON Schema 字符串；为空时使用 object 空 schema
 * @param enabled 工具是否启用，P2-6 默认跟随 server 工具列表启用
 */
public record McpToolDescriptor(
        String serverId,
        String toolName,
        String namespacedName,
        String description,
        String inputSchema,
        boolean enabled
) {

    /**
     * 创建启用状态的 MCP 工具描述符。
     */
    public static McpToolDescriptor of(String serverId, String toolName, String description, String inputSchema) {
        return new McpToolDescriptor(
                serverId,
                toolName,
                "mcp." + serverId + "." + toolName,
                description == null || description.isBlank() ? "MCP tool " + toolName : description,
                inputSchema == null || inputSchema.isBlank() ? "{\"type\":\"object\"}" : inputSchema,
                true);
    }
}
