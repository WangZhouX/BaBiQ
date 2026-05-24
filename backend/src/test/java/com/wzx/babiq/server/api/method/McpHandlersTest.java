package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.mcp.McpClientManager;
import com.wzx.babiq.server.mcp.McpServerStatus;
import com.wzx.babiq.server.mcp.McpToolDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP JSON-RPC handler 测试。
 *
 * <p>桌面端只需要 server 状态、工具列表和手动刷新入口。handler 不直接碰 SDK，
 * 只调用 McpClientManager，避免协议层和外部进程生命周期耦合。</p>
 */
class McpHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("mcp/servers/list 返回 server 状态列表")
    void servers_list_should_return_server_statuses() {
        McpClientManager manager = mock(McpClientManager.class);
        List<McpServerStatus> statuses = List.of(new McpServerStatus(
                "local-filesystem", "本地文件 MCP", "stdio", true, "connected", 2, null));
        when(manager.servers()).thenReturn(statuses);
        McpServersListHandler handler = new McpServersListHandler(manager);

        Object result = handler.handle(objectMapper.nullNode(), null);

        assertThat(handler.method()).isEqualTo("mcp/servers/list");
        assertThat(result).isEqualTo(Map.of("servers", statuses));
    }

    @Test
    @DisplayName("mcp/tools/list 按 serverId 返回工具列表")
    void tools_list_should_return_tools_for_server() {
        McpClientManager manager = mock(McpClientManager.class);
        List<McpToolDescriptor> tools = List.of(McpToolDescriptor.of("local-filesystem", "read_file", "Read file", "{}"));
        when(manager.tools("local-filesystem")).thenReturn(tools);
        McpToolsListHandler handler = new McpToolsListHandler(manager);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("serverId", "local-filesystem")), null);

        assertThat(handler.method()).isEqualTo("mcp/tools/list");
        assertThat(result).isEqualTo(Map.of("serverId", "local-filesystem", "tools", tools));
    }

    @Test
    @DisplayName("mcp/servers/refresh 触发 manager 刷新")
    void servers_refresh_should_delegate_to_manager() {
        McpClientManager manager = mock(McpClientManager.class);
        McpServerStatus status = new McpServerStatus("local-filesystem", "本地文件 MCP", "stdio", true, "connected", 1, null);
        when(manager.refresh("local-filesystem")).thenReturn(status);
        McpServersRefreshHandler handler = new McpServersRefreshHandler(manager);

        Object result = handler.handle(objectMapper.valueToTree(Map.of("serverId", "local-filesystem")), null);

        assertThat(result).isEqualTo(Map.of("server", status));
        verify(manager).refresh("local-filesystem");
    }

    @Test
    @DisplayName("缺少 serverId 时返回 JSON-RPC INVALID_PARAMS")
    void missing_server_id_should_throw_invalid_params() {
        McpToolsListHandler handler = new McpToolsListHandler(mock(McpClientManager.class));

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
