package com.wzx.babiq.server.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.tool.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * 把 MCP 工具适配成 Spring AI `ToolCallback`。
 *
 * <p>该类同时实现 BaBiQ 的 {@link Tool} 标记接口和 Spring AI 的 {@link ToolCallback}。
 * 这样 ToolRegistry 可以把 MCP 动态工具和本地静态工具合并给 ReactAgent，随后统一经过
 * HITL、沙箱、观测、spotlighting 等既有链路。</p>
 */
public class McpToolAdapter implements Tool, ToolCallback {

    /** 解析工具入参 JSON 的类型引用。 */
    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };

    /** MCP 工具描述符，包含 serverId、原始工具名和命名空间工具名。 */
    private final McpToolDescriptor descriptor;
    /** MCP manager，负责真正把调用路由到已连接 server。 */
    private final McpClientManager manager;
    /** Jackson mapper，解析模型传入的工具参数 JSON。 */
    private final ObjectMapper objectMapper;
    /** Spring AI 看到的工具定义。 */
    private final ToolDefinition toolDefinition;

    /**
     * 创建 MCP 工具适配器。
     */
    public McpToolAdapter(McpToolDescriptor descriptor, McpClientManager manager, ObjectMapper objectMapper) {
        this.descriptor = descriptor;
        this.manager = manager;
        this.objectMapper = objectMapper;
        this.toolDefinition = ToolDefinition.builder()
                .name(descriptor.namespacedName())
                .description(descriptor.description())
                .inputSchema(descriptor.inputSchema())
                .build();
    }

    @Override
    public String name() {
        return descriptor.namespacedName();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String input) {
        Map<String, Object> arguments = parseArguments(input);
        McpToolResult result = manager.callTool(descriptor.serverId(), descriptor.toolName(), arguments);
        if (!result.success()) {
            throw new IllegalStateException(result.error());
        }
        return result.output();
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        return call(input);
    }

    private Map<String, Object> parseArguments(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(input, ARGUMENTS_TYPE);
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP 工具参数不是合法 JSON 对象: " + exception.getMessage(), exception);
        }
    }
}
