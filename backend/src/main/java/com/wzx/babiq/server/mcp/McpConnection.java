package com.wzx.babiq.server.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个已经初始化完成的 MCP server 连接。
 *
 * <p>接口用于隔离官方 SDK 细节：McpClientManager 只关心拉工具和调用工具，
 * 单元测试可以用 FakeConnection 验证 BaBiQ 生命周期，不需要真的启动外部进程。</p>
 */
public interface McpConnection extends AutoCloseable {

    /**
     * 从 MCP server 拉取当前工具列表。
     */
    List<McpToolDescriptor> listTools();

    /**
     * 调用某个 MCP 工具。
     *
     * @param toolName MCP server 原始工具名
     * @param arguments 工具参数 Map
     * @return MCP 调用结果
     */
    McpToolResult callTool(String toolName, Map<String, Object> arguments);

    /**
     * 关闭底层外部进程或传输连接。
     */
    @Override
    void close();
}
