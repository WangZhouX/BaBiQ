package com.wzx.babiq.server.mcp;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP client 运行期管理器。
 *
 * <p>它负责把受信任配置变成已连接的 MCP server，并维护 BaBiQ 可见的动态工具目录。
 * 连接失败只影响对应 MCP server，不会阻止主聊天后端启动。</p>
 */
@Service
public class McpClientManager implements DisposableBean {

    /** manager 日志；只记录 serverId 和失败原因，不打印敏感环境变量。 */
    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** MCP 全局配置。 */
    private final McpProperties properties;
    /** 连接工厂，生产环境使用官方 SDK，测试使用 Fake。 */
    private final McpClientConnector connector;
    /** SQLite 持久化服务，用于设置页和重启后的状态参考。 */
    private final McpPersistenceService persistenceService;
    /** 当前已连接 server，key 为 serverId。 */
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    /** 当前内存工具目录，key 为 serverId。 */
    private final Map<String, List<McpToolDescriptor>> toolsByServer = new ConcurrentHashMap<>();
    /** 当前 server 状态，key 为 serverId。 */
    private final Map<String, McpServerStatus> statuses = new ConcurrentHashMap<>();

    /**
     * 创建 MCP client manager。
     */
    public McpClientManager(McpProperties properties, McpClientConnector connector, McpPersistenceService persistenceService) {
        this.properties = properties;
        this.connector = connector;
        this.persistenceService = persistenceService;
    }

    /**
     * 启动时同步配置并连接所有启用 server。
     */
    @PostConstruct
    public void bootstrap() {
        persistenceService.bootstrapYamlServers(properties.servers());
        if (!properties.enabled()) {
            markConfiguredServersAsDisabled();
            return;
        }
        for (McpServerConfig config : properties.enabledServers()) {
            refresh(config.id());
        }
        for (McpServerConfig config : properties.servers()) {
            if (!config.enabled()) {
                updateStatus(config, "disabled", 0, null);
            }
        }
    }

    /**
     * 返回所有配置中出现的 server 状态。
     */
    public List<McpServerStatus> servers() {
        List<McpServerStatus> result = new ArrayList<>();
        for (McpServerConfig config : properties.servers()) {
            result.add(statuses.getOrDefault(config.id(), defaultStatus(config)));
        }
        return List.copyOf(result);
    }

    /**
     * 返回某个 server 的工具列表。
     */
    public List<McpToolDescriptor> tools(String serverId) {
        List<McpToolDescriptor> inMemory = toolsByServer.get(serverId);
        if (inMemory != null) {
            return inMemory;
        }
        return persistenceService.listTools(serverId);
    }

    /**
     * 返回所有已发现的 MCP 工具。
     */
    public List<McpToolDescriptor> allTools() {
        Map<String, McpToolDescriptor> merged = new LinkedHashMap<>();
        for (String serverId : toolsByServer.keySet()) {
            for (McpToolDescriptor descriptor : tools(serverId)) {
                merged.put(descriptor.namespacedName(), descriptor);
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 手动刷新一个 server 的连接和工具目录。
     */
    public synchronized McpServerStatus refresh(String serverId) {
        McpServerConfig config = findConfig(serverId);
        closeConnection(serverId);
        toolsByServer.remove(serverId);
        if (!properties.enabled() || !config.enabled()) {
            return updateStatus(config, "disabled", 0, null);
        }
        try {
            McpConnection connection = connector.connect(config, properties.requestTimeout());
            List<McpToolDescriptor> tools = List.copyOf(connection.listTools());
            connections.put(serverId, connection);
            toolsByServer.put(serverId, tools);
            persistenceService.replaceTools(serverId, tools, Instant.now());
            return updateStatus(config, "connected", tools.size(), null);
        } catch (RuntimeException exception) {
            log.warn("MCP server 连接失败: serverId={}, reason={}", serverId, exception.getMessage());
            return updateStatus(config, "failed", 0, exception.getMessage());
        }
    }

    /**
     * 调用一个 MCP 工具。
     */
    public McpToolResult callTool(String serverId, String toolName, Map<String, Object> arguments) {
        McpConnection connection = connections.get(serverId);
        if (connection == null) {
            throw new IllegalStateException("MCP server 未连接: " + serverId);
        }
        return connection.callTool(toolName, arguments == null ? Map.of() : Map.copyOf(arguments));
    }

    @Override
    public void destroy() {
        for (String serverId : List.copyOf(connections.keySet())) {
            closeConnection(serverId);
        }
    }

    private void markConfiguredServersAsDisabled() {
        for (McpServerConfig config : properties.servers()) {
            updateStatus(config, "disabled", 0, null);
        }
    }

    private McpServerConfig findConfig(String serverId) {
        return properties.servers().stream()
                .filter(config -> config.id().equals(serverId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP server 不存在: " + serverId));
    }

    private McpServerStatus updateStatus(McpServerConfig config, String status, int toolCount, String lastError) {
        McpServerStatus view = new McpServerStatus(
                config.id(),
                config.displayName(),
                config.transport(),
                config.enabled(),
                status,
                toolCount,
                lastError);
        statuses.put(config.id(), view);
        persistenceService.markServerStatus(config.id(), status, lastError, Instant.now());
        return view;
    }

    private McpServerStatus defaultStatus(McpServerConfig config) {
        String status = !properties.enabled() || !config.enabled() ? "disabled" : "configured";
        int toolCount = tools(config.id()).size();
        return new McpServerStatus(config.id(), config.displayName(), config.transport(), config.enabled(), status, toolCount, null);
    }

    private void closeConnection(String serverId) {
        McpConnection previous = connections.remove(serverId);
        if (previous != null) {
            previous.close();
        }
    }
}
