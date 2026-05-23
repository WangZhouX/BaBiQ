package com.wzx.babiq.server.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表。
 *
 * <p>只负责发现、命名和导出 ToolCallback，不做沙箱判断，不做结果截断。</p>
 */
@Component
public class ToolRegistry {

    /** toolName -> BaBiQ 工具实例，Agent 执行工具调用时通过名称查找实现。 */
    private final Map<String, Tool> toolsByName;
    /** 适配给 Spring AI 的 ToolCallback 数组，ReactAgent 只认识这一层抽象。 */
    private final ToolCallback[] callbacks;

    /**
     * 构造工具注册表。
     *
     * @param tools Spring 容器中所有工具实现
     */
    public ToolRegistry(List<Tool> tools) {
        List<Tool> safeTools = tools == null ? List.of() : List.copyOf(tools);
        this.toolsByName = indexTools(safeTools);
        // Spring AI 的 MethodToolCallbackProvider 会扫描 @Tool 注解方法并生成模型可调用的 ToolCallback。
        this.callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(safeTools.toArray())
                .build()
                .getToolCallbacks();
    }

    /**
     * 按名称获取工具。
     *
     * @param name 工具名
     * @return 对应工具
     */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    /**
     * 返回全部工具名。
     *
     * @return 工具名列表
     */
    public List<String> names() {
        return List.copyOf(toolsByName.keySet());
    }

    /**
     * 导出全部 ToolCallback。
     *
     * @return 工具回调数组
     */
    public ToolCallback[] allCallbacks() {
        // 返回数组副本，避免外部调用者修改内部 callbacks。
        return Arrays.copyOf(callbacks, callbacks.length);
    }

    /**
     * 建立工具名索引，并在启动期发现重复工具名。
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
}
