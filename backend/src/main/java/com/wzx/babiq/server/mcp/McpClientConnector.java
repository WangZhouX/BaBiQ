package com.wzx.babiq.server.mcp;

import java.time.Duration;

/**
 * MCP 连接工厂。
 *
 * <p>生产环境实现会使用官方 MCP Java SDK；测试环境可以替换成内存 Fake。
 * 把 SDK 创建逻辑藏在这个边界里，避免 manager、handler、ToolAdapter 都直接依赖外部进程细节。</p>
 */
public interface McpClientConnector {

    /**
     * 创建并初始化一个 MCP 连接。
     *
     * @param config server 配置
     * @param requestTimeout 同步请求超时
     * @return 已初始化连接
     */
    McpConnection connect(McpServerConfig config, Duration requestTimeout);
}
