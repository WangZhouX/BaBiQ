package com.wzx.babiq.server.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * MCP client 全局配置。
 *
 * <p>绑定 {@code babiq.mcp.*}。默认 disabled 是有意设计：P2-6 引入的是外部进程能力，
 * 只有用户在后端配置里明确开启后才会启动 stdio server，避免升级后自动执行本地命令。</p>
 *
 * @param enabled 全局开关；false 时任何 server 都不会启动
 * @param requestTimeout MCP listTools/callTool 的同步请求超时时间
 * @param servers 后端受信任配置中的 MCP server 列表
 */
@ConfigurationProperties(prefix = "babiq.mcp")
public record McpProperties(
        boolean enabled,
        Duration requestTimeout,
        List<McpServerConfig> servers
) {

    /**
     * 补齐默认值。
     */
    public McpProperties {
        requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofSeconds(20)
                : requestTimeout;
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    /**
     * 返回需要启动连接的 server。
     *
     * <p>调用这个方法时才做启动校验，可以让全局 disabled 的配置文件保留不完整示例而不影响应用启动。</p>
     */
    public List<McpServerConfig> enabledServers() {
        if (!enabled) {
            return List.of();
        }
        return servers.stream()
                .filter(McpServerConfig::enabled)
                .peek(McpServerConfig::validateForStartup)
                .toList();
    }
}
