package com.wzx.babiq.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP 工具适配器测试。
 *
 * <p>MCP 工具最终必须表现成 Spring AI `ToolCallback`，这样才能继续经过现有
 * HITL、沙箱、观测和 spotlighting 拦截链，而不是绕开 BaBiQ 的安全边界。</p>
 */
class McpToolAdapterTest {

    @Test
    @DisplayName("MCP 工具名称会被命名空间化并调用 manager")
    void adapter_should_namespace_tool_and_delegate_call() {
        McpClientManager manager = mock(McpClientManager.class);
        when(manager.callTool(eq("local-filesystem"), eq("read_file"), eq(Map.of("path", "README.md"))))
                .thenReturn(McpToolResult.success("file content"));
        McpToolAdapter adapter = new McpToolAdapter(
                McpToolDescriptor.of("local-filesystem", "read_file", "Read file", "{\"type\":\"object\"}"),
                manager,
                new ObjectMapper());

        String result = adapter.call("{\"path\":\"README.md\"}");

        assertThat(adapter).isInstanceOf(ToolCallback.class);
        assertThat(adapter.name()).isEqualTo("mcp.local-filesystem.read_file");
        assertThat(adapter.getToolDefinition().name()).isEqualTo("mcp.local-filesystem.read_file");
        assertThat(adapter.getToolDefinition().inputSchema()).contains("object");
        assertThat(result).isEqualTo("file content");
        verify(manager).callTool("local-filesystem", "read_file", Map.of("path", "README.md"));
    }

    @Test
    @DisplayName("MCP 工具失败时抛出异常，交给 ToolObservationInterceptor 记录 failed")
    void adapter_should_throw_when_mcp_reports_error() {
        McpClientManager manager = mock(McpClientManager.class);
        when(manager.callTool(eq("local-filesystem"), eq("write_file"), eq(Map.of())))
                .thenReturn(McpToolResult.failure("MCP tool denied"));
        McpToolAdapter adapter = new McpToolAdapter(
                McpToolDescriptor.of("local-filesystem", "write_file", "Write file", "{}"),
                manager,
                new ObjectMapper());

        assertThatThrownBy(() -> adapter.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP tool denied");
    }
}
