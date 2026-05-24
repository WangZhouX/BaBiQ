package com.wzx.babiq.server.mcp;

/**
 * MCP server 对桌面端暴露的状态视图。
 *
 * @param serverId server 稳定标识
 * @param displayName server 展示名称
 * @param transport 传输类型，P2-6 固定为 stdio
 * @param enabled 是否在配置中启用
 * @param status 当前状态，disabled、configured、connected 或 failed
 * @param toolCount 当前已发现的工具数量
 * @param lastError 最近一次连接或刷新失败原因；成功时为空
 */
public record McpServerStatus(
        String serverId,
        String displayName,
        String transport,
        boolean enabled,
        String status,
        int toolCount,
        String lastError
) {
}
