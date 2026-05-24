package com.wzx.babiq.server.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于官方 Model Context Protocol Java SDK 的 stdio MCP 连接器。
 *
 * <p>本类是 BaBiQ 与 SDK 的唯一直接集成点。Spring AI MCP starter 在当前依赖树里会受到
 * Spring AI Alibaba 旧版 MCP SDK 传递依赖影响，所以 P2-6 使用官方 SDK 稳定版做薄封装。</p>
 */
@Component
public class SdkMcpClientConnector implements McpClientConnector {

    /** SDK 连接日志，只记录 serverId 和错误原因，不打印环境变量或敏感参数。 */
    private static final Logger log = LoggerFactory.getLogger(SdkMcpClientConnector.class);

    /** MCP SDK 1.1.x 使用 Jackson 3 的 mapper；它和 Spring Boot 的 Jackson 2 mapper 分开。 */
    private final McpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

    @Override
    public McpConnection connect(McpServerConfig config, Duration requestTimeout) {
        config.validateForStartup();
        ServerParameters parameters = ServerParameters.builder(config.command())
                .args(config.args())
                .build();
        WorkingDirectoryStdioClientTransport transport = new WorkingDirectoryStdioClientTransport(
                parameters, mcpJsonMapper, config.cwd());
        transport.setStdErrorHandler(line -> log.debug("MCP server stderr: serverId={}, line={}", config.id(), line));

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(requestTimeout)
                .build();
        client.initialize();
        return new SdkMcpConnection(config.id(), client, mcpJsonMapper);
    }

    /**
     * 支持 cwd 的 stdio transport。
     *
     * <p>官方 transport 暴露了 protected ProcessBuilder 钩子，所以这里只覆盖工作目录，
     * 不修改 command/args/env，避免引入额外执行面。</p>
     */
    private static final class WorkingDirectoryStdioClientTransport extends StdioClientTransport {

        /** 进程工作目录；为空时沿用后端进程目录。 */
        private final String cwd;

        private WorkingDirectoryStdioClientTransport(
                ServerParameters parameters,
                McpJsonMapper mcpJsonMapper,
                String cwd) {
            super(parameters, mcpJsonMapper);
            this.cwd = cwd;
        }

        @Override
        protected ProcessBuilder getProcessBuilder() {
            ProcessBuilder builder = super.getProcessBuilder();
            if (cwd != null && !cwd.isBlank()) {
                builder.directory(Path.of(cwd).toFile());
            }
            return builder;
        }
    }

    /**
     * 官方 SDK 同步客户端的 BaBiQ 包装。
     */
    private static final class SdkMcpConnection implements McpConnection {

        /** serverId 只用于日志和工具命名空间。 */
        private final String serverId;
        /** 官方同步 MCP client。 */
        private final McpSyncClient client;
        /** SDK mapper，用于序列化 SDK schema 和结构化结果。 */
        private final McpJsonMapper mcpJsonMapper;

        private SdkMcpConnection(String serverId, McpSyncClient client, McpJsonMapper mcpJsonMapper) {
            this.serverId = serverId;
            this.client = client;
            this.mcpJsonMapper = mcpJsonMapper;
        }

        @Override
        public List<McpToolDescriptor> listTools() {
            return client.listTools().tools().stream()
                    .map(this::toDescriptor)
                    .toList();
        }

        @Override
        public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            String text = resultText(result);
            if (Boolean.TRUE.equals(result.isError())) {
                return McpToolResult.failure(text);
            }
            return McpToolResult.success(text);
        }

        @Override
        public void close() {
            client.closeGracefully();
        }

        private McpToolDescriptor toDescriptor(McpSchema.Tool tool) {
            return McpToolDescriptor.of(serverId, tool.name(), tool.description(), schemaJson(tool.inputSchema()));
        }

        private String schemaJson(McpSchema.JsonSchema schema) {
            if (schema == null) {
                return "{\"type\":\"object\"}";
            }
            try {
                return mcpJsonMapper.writeValueAsString(schema);
            } catch (Exception exception) {
                return "{\"type\":\"object\"}";
            }
        }

        private String resultText(McpSchema.CallToolResult result) {
            List<String> textContent = result.content() == null
                    ? List.of()
                    : result.content().stream()
                    .map(content -> content instanceof McpSchema.TextContent text ? text.text() : String.valueOf(content))
                    .toList();
            if (result.structuredContent() == null) {
                return String.join("\n", textContent);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", textContent);
            payload.put("structuredContent", result.structuredContent());
            try {
                return mcpJsonMapper.writeValueAsString(payload);
            } catch (Exception exception) {
                return String.join("\n", textContent);
            }
        }
    }
}
