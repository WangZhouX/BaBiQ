package com.wzx.babiq.server.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 配置模型测试。
 *
 * <p>P2-6 只允许从受信任配置中读取本地 stdio server，不能让 UI 随意输入命令后立即执行。
 * 因此配置对象本身要承担最早的一层边界校验。</p>
 */
class McpPropertiesTest {

    @Test
    @DisplayName("全局 disabled 时不返回任何待连接 server")
    void disabled_global_properties_should_not_start_servers() {
        McpProperties properties = new McpProperties(false, Duration.ofSeconds(5), List.of(sampleServer(true)));

        assertThat(properties.enabledServers()).isEmpty();
    }

    @Test
    @DisplayName("启用的 stdio server 必须配置 command")
    void enabled_stdio_server_should_require_command() {
        McpServerConfig server = new McpServerConfig(
                "local-filesystem",
                "本地文件 MCP",
                "stdio",
                " ",
                List.of(),
                "E:\\BaBiQ",
                true,
                "ON_REQUEST");
        McpProperties properties = new McpProperties(true, Duration.ofSeconds(5), List.of(server));

        assertThatThrownBy(properties::enabledServers)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("command");
    }

    @Test
    @DisplayName("args 和 cwd 会被保留并做不可变拷贝")
    void args_and_cwd_should_be_bound_and_copied() {
        McpServerConfig server = sampleServer(true);
        McpProperties properties = new McpProperties(true, Duration.ZERO, List.of(server));

        McpServerConfig enabled = properties.enabledServers().get(0);

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(enabled.id()).isEqualTo("local-filesystem");
        assertThat(enabled.args()).containsExactly("server.js", "--readonly");
        assertThat(enabled.cwd()).isEqualTo("E:\\BaBiQ");
        assertThatThrownBy(() -> enabled.args().add("--unsafe")).isInstanceOf(UnsupportedOperationException.class);
    }

    private static McpServerConfig sampleServer(boolean enabled) {
        return new McpServerConfig(
                "local-filesystem",
                "本地文件 MCP",
                "stdio",
                "node",
                List.of("server.js", "--readonly"),
                "E:\\BaBiQ",
                enabled,
                "ON_REQUEST");
    }
}
