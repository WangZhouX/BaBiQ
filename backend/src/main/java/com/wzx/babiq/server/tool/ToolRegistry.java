package com.wzx.babiq.server.tool;

import com.wzx.babiq.server.mcp.McpToolCatalog;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表。
 *
 * <p>该类负责把 BaBiQ 本地工具和 P2-6 MCP 动态工具合并成 ReactAgent 可识别的
 * Spring AI `ToolCallback` 数组。它不做审批、沙箱或结果截断，这些横切能力仍由
 * ReActStrategy 挂载的官方 interceptor/hook 统一处理。</p>
 */
@Component
public class ToolRegistry {

    /** 本地静态工具索引，key 是工具名，例如 read_file、exec_shell。 */
    private final Map<String, Tool> toolsByName;
    /** 本地静态工具转换出的 Spring AI callback；MCP callback 每次导出时动态追加。 */
    private final ToolCallback[] callbacks;
    /** MCP 动态工具目录懒加载引用；未启用 MCP 或单元测试旧构造器中可以为空。 */
    private final ObjectProvider<McpToolCatalog> mcpToolCatalogProvider;

    /**
     * 测试用构造器，只注册本地静态工具。
     *
     * @param tools 本地工具列表
     */
    public ToolRegistry(List<Tool> tools) {
        this(tools, null);
    }

    /**
     * 生产构造器，注册本地工具并接入 MCP 动态工具目录。
     *
     * @param tools Spring 容器中所有本地工具实现
     * @param mcpToolCatalogProvider MCP 动态工具目录懒加载引用，用于避免启动期循环依赖
     */
    @Autowired
    public ToolRegistry(List<Tool> tools, ObjectProvider<McpToolCatalog> mcpToolCatalogProvider) {
        List<Tool> safeTools = tools == null ? List.of() : List.copyOf(tools);
        this.toolsByName = indexTools(safeTools);
        this.mcpToolCatalogProvider = mcpToolCatalogProvider;
        // MethodToolCallbackProvider 只负责 @Tool 本地方法；MCP adapter 本身已经实现 ToolCallback。
        this.callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(safeTools.toArray())
                .build()
                .getToolCallbacks();
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名
     * @return 本地工具或 MCP 动态工具
     */
    public Optional<Tool> get(String name) {
        Tool local = toolsByName.get(name);
        if (local != null) {
            return Optional.of(local);
        }
        McpToolCatalog catalog = mcpToolCatalog();
        return catalog == null ? Optional.empty() : catalog.get(name);
    }

    /**
     * 返回所有当前可用工具名。
     *
     * <p>MCP 工具目录会随 refresh 变化，所以这里每次调用都重新读取 catalog。</p>
     */
    public List<String> names() {
        List<String> names = new ArrayList<>(toolsByName.keySet());
        McpToolCatalog catalog = mcpToolCatalog();
        if (catalog != null) {
            names.addAll(catalog.names());
        }
        return List.copyOf(names);
    }

    /**
     * 导出全部 Spring AI ToolCallback。
     *
     * @return 本地工具 callback 和 MCP 动态工具 callback 的数组副本
     */
    public ToolCallback[] allCallbacks() {
        McpToolCatalog catalog = mcpToolCatalog();
        if (catalog == null) {
            return Arrays.copyOf(callbacks, callbacks.length);
        }
        ToolCallback[] mcpCallbacks = catalog.callbacks();
        ToolCallback[] merged = Arrays.copyOf(callbacks, callbacks.length + mcpCallbacks.length);
        System.arraycopy(mcpCallbacks, 0, merged, callbacks.length, mcpCallbacks.length);
        return merged;
    }

    /**
     * 建立本地工具名称索引，并在启动期发现重复工具名。
     */
    private Map<String, Tool> indexTools(List<Tool> tools) {
        Map<String, Tool> indexed = new LinkedHashMap<>();
        for (Tool tool : tools) {
            Tool previous = indexed.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Duplicate tool name: " + tool.name());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * 懒加载 MCP 工具目录。
     *
     * <p>为空时代表 MCP 未启用或测试环境没有注入 catalog，ToolRegistry 会自动退化为只暴露本地工具。</p>
     */
    private McpToolCatalog mcpToolCatalog() {
        return mcpToolCatalogProvider == null ? null : mcpToolCatalogProvider.getIfAvailable();
    }
}
