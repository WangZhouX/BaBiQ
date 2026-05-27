package com.wzx.babiq.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.tool.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * MCP 动态工具目录。
 *
 * <p>ToolRegistry 每次导出 callback 时会读取这里，因此 MCP refresh 后新工具无需重启后端就能进入下一轮 turn。
 * P2-6 只做本地 stdio 的最小动态目录，不做 marketplace 和自动安装。</p>
 */
@Component
public class McpToolCatalog {

    /** MCP manager，提供当前已发现的所有工具。 */
    private final McpClientManager manager;
    /** 传给 ToolAdapter 的 JSON mapper。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 MCP 工具目录。
     */
    public McpToolCatalog(McpClientManager manager, ObjectMapper objectMapper) {
        this.manager = manager;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回所有 MCP 工具名。
     */
    public List<String> names() {
        return manager.allTools().stream()
                .map(McpToolDescriptor::namespacedName)
                .toList();
    }

    /**
     * 按名称查找一个 MCP 工具。
     */
    public Optional<Tool> get(String name) {
        return manager.allTools().stream()
                .filter(descriptor -> descriptor.namespacedName().equals(name))
                .findFirst()
                .map(descriptor -> new McpToolAdapter(descriptor, manager, objectMapper));
    }

    /**
     * 导出 Spring AI ToolCallback。
     */
    public ToolCallback[] callbacks() {
        return manager.allTools().stream()
                .map(descriptor -> new McpToolAdapter(descriptor, manager, objectMapper))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 返回当前 MCP 工具描述符。
     *
     * <p>能力目录同步只需要轻量 metadata，不应该为了展示和搜索重新构造 ToolCallback。</p>
     */
    public List<McpToolDescriptor> descriptors() {
        return manager.allTools();
    }
}
