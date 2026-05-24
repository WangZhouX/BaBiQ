package com.wzx.babiq.server.mcp;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.interceptor.SpotlightingToolInterceptor;
import com.wzx.babiq.server.interceptor.ToolObservationInterceptor;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.security.Spotlighter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P2-6 最小端到端验收。
 *
 * <p>这里不启动真实第三方 MCP server，而是用 FakeConnector 验证 BaBiQ 内部端到端链路：
 * manager 拉取工具 -> adapter 调用工具 -> ToolObservationInterceptor 计数 -> Spotlighting 包裹输出。</p>
 */
class McpEndToEndIT {

    @Test
    @DisplayName("MCP 工具调用继续经过观测和 spotlighting 链路")
    void mcp_tool_should_flow_through_observation_and_spotlighting() {
        McpClientManager manager = new McpClientManager(properties(), new FakeConnector(), mock(McpPersistenceService.class));
        manager.bootstrap();
        McpToolDescriptor descriptor = manager.tools("local-filesystem").get(0);
        McpToolAdapter adapter = new McpToolAdapter(descriptor, manager, new ObjectMapper());
        BaBiQMetrics metrics = new BaBiQMetrics();
        ToolObservationInterceptor observation = new ToolObservationInterceptor(metrics);
        SpotlightingToolInterceptor spotlighting = new SpotlightingToolInterceptor(new Spotlighter());
        TurnObservationContext context = TurnObservationContext.start("thread-1", "turn-1", "deepseek", "deepseek-v4-pro");
        ToolCallRequest request = new ToolCallRequest(
                adapter.name(),
                "{\"path\":\"README.md\"}",
                "tool-1",
                Map.of(TurnObservationContext.METADATA_KEY, context));

        ToolCallResponse response = observation.interceptToolCall(request, observed ->
                spotlighting.interceptToolCall(observed, inner ->
                        new ToolCallResponse(adapter.call(inner.getArguments()), inner.getToolName(), inner.getToolCallId())));

        assertThat(response.getResult()).startsWith("<untrusted-data source=\"tool:mcp.local-filesystem.read_file\"");
        assertThat(response.getResult()).contains("hello from mcp");
        assertThat(context.toolCalls()).isEqualTo(1);
        assertThat(context.toolCallsByName()).containsEntry("mcp.local-filesystem.read_file", 1L);
        assertThat(metrics.snapshot().toolCallsByName()).containsEntry("mcp.local-filesystem.read_file", 1L);
    }

    private static McpProperties properties() {
        return new McpProperties(true, Duration.ofSeconds(2), List.of(new McpServerConfig(
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

        @Override
        public McpConnection connect(McpServerConfig config, Duration requestTimeout) {
            return new McpConnection() {
                @Override
                public List<McpToolDescriptor> listTools() {
                    return List.of(McpToolDescriptor.of(config.id(), "read_file", "Read file", "{\"type\":\"object\"}"));
                }

                @Override
                public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
                    return McpToolResult.success("hello from mcp");
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
