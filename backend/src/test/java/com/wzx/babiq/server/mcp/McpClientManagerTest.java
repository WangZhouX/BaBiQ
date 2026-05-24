package com.wzx.babiq.server.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * MCP client manager 测试。
 *
 * <p>这里用 Fake connector 替代真实 stdio 进程，重点验证 BaBiQ 自己的生命周期语义：
 * 启动失败不能拖垮主服务，刷新会重建连接，工具目录会同步到运行期和 SQLite。</p>
 */
class McpClientManagerTest {

    @Test
    @DisplayName("enabled server 启动后连接并拉取工具")
    void bootstrap_should_connect_enabled_servers_and_cache_tools() {
        FakeConnector connector = new FakeConnector();
        McpPersistenceService persistence = mock(McpPersistenceService.class);
        McpClientManager manager = new McpClientManager(properties(true), connector, persistence);

        manager.bootstrap();

        assertThat(connector.connectedServerIds).containsExactly("local-filesystem");
        assertThat(manager.servers()).singleElement()
                .satisfies(status -> {
                    assertThat(status.serverId()).isEqualTo("local-filesystem");
                    assertThat(status.status()).isEqualTo("connected");
                    assertThat(status.toolCount()).isEqualTo(1);
                    assertThat(status.lastError()).isNull();
                });
        assertThat(manager.tools("local-filesystem")).extracting(McpToolDescriptor::namespacedName)
                .containsExactly("mcp.local-filesystem.read_file");
        verify(persistence).bootstrapYamlServers(anyList());
        verify(persistence).replaceTools(eq("local-filesystem"), anyList(), any());
    }

    @Test
    @DisplayName("连接失败只记录 failed 状态，不抛出启动异常")
    void bootstrap_should_record_failure_without_throwing() {
        FakeConnector connector = new FakeConnector();
        connector.failNextConnect = true;
        McpPersistenceService persistence = mock(McpPersistenceService.class);
        McpClientManager manager = new McpClientManager(properties(true), connector, persistence);

        manager.bootstrap();

        assertThat(manager.servers()).singleElement()
                .satisfies(status -> {
                    assertThat(status.status()).isEqualTo("failed");
                    assertThat(status.lastError()).contains("boom");
                    assertThat(status.toolCount()).isZero();
                });
    }

    @Test
    @DisplayName("refresh 会关闭旧连接并重新拉取工具")
    void refresh_should_reconnect_one_server() {
        FakeConnector connector = new FakeConnector();
        McpClientManager manager = new McpClientManager(properties(true), connector, mock(McpPersistenceService.class));
        manager.bootstrap();

        McpServerStatus refreshed = manager.refresh("local-filesystem");

        assertThat(connector.connectedServerIds).containsExactly("local-filesystem", "local-filesystem");
        assertThat(connector.closedConnections).isEqualTo(1);
        assertThat(refreshed.status()).isEqualTo("connected");
    }

    @Test
    @DisplayName("全局 disabled 时不连接 server")
    void disabled_properties_should_not_connect() {
        FakeConnector connector = new FakeConnector();
        McpClientManager manager = new McpClientManager(properties(false), connector, mock(McpPersistenceService.class));

        manager.bootstrap();

        assertThat(connector.connectedServerIds).isEmpty();
        assertThat(manager.servers()).singleElement()
                .satisfies(status -> assertThat(status.status()).isEqualTo("disabled"));
    }

    private static McpProperties properties(boolean enabled) {
        return new McpProperties(enabled, Duration.ofSeconds(2), List.of(new McpServerConfig(
                "local-filesystem",
                "本地文件 MCP",
                "stdio",
                "node",
                List.of("server.js"),
                "E:\\BaBiQ",
                true,
                "ON_REQUEST")));
    }

    private static final class FakeConnector implements McpClientConnector {
        private final List<String> connectedServerIds = new ArrayList<>();
        private int closedConnections = 0;
        private boolean failNextConnect = false;

        @Override
        public McpConnection connect(McpServerConfig config, Duration requestTimeout) {
            connectedServerIds.add(config.id());
            if (failNextConnect) {
                failNextConnect = false;
                throw new IllegalStateException("boom");
            }
            return new McpConnection() {
                @Override
                public List<McpToolDescriptor> listTools() {
                    return List.of(McpToolDescriptor.of(config.id(), "read_file", "Read file", "{}"));
                }

                @Override
                public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
                    return McpToolResult.success("ok");
                }

                @Override
                public void close() {
                    closedConnections++;
                }
            };
        }
    }
}
