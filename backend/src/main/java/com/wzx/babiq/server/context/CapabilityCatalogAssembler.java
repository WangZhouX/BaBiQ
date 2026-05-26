package com.wzx.babiq.server.context;

import com.wzx.babiq.server.context.model.CapabilityCatalog;
import com.wzx.babiq.server.context.model.CapabilityDescriptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 能力目录装配器。
 *
 * <p>Spring AI 的 {@link ToolCallback} 是真实工具调用 schema 的载体，但 P3 的上下文 envelope
 * 只应该注入能力摘要。该装配器从 callback 中提取名称和说明，丢弃 input schema，
 * 为后续按需工具装配保留清晰边界。</p>
 */
@Component
public class CapabilityCatalogAssembler {

    /**
     * 从 Spring AI ToolCallback 列表构建模型可读的能力目录。
     *
     * @param callbacks 当前候选工具 callback；为空时返回空目录
     * @return 不包含 input schema 的能力目录摘要
     */
    public CapabilityCatalog assemble(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new CapabilityCatalog(List.of());
        }
        List<CapabilityDescriptor> descriptors = Arrays.stream(callbacks)
                .map(callback -> new CapabilityDescriptor(
                        callback.getToolDefinition().name(),
                        sourceOf(callback.getToolDefinition().name()),
                        safeDescription(callback),
                        approvalRequired(callback.getToolDefinition().name()),
                        riskLevel(callback.getToolDefinition().name())))
                .toList();
        return new CapabilityCatalog(descriptors);
    }

    /**
     * 根据工具命名空间判断来源，P3-5 可以继续扩展到 skill/app/plugin。
     */
    private String sourceOf(String name) {
        return name != null && name.startsWith("mcp.") ? "mcp" : "local";
    }

    /**
     * 读取工具说明，缺省时降级为工具名，避免 envelope 里出现空描述。
     */
    private String safeDescription(ToolCallback callback) {
        String description = callback.getToolDefinition().description();
        if (description == null || description.isBlank()) {
            return callback.getToolDefinition().name();
        }
        return description;
    }

    /**
     * 判断工具是否需要审批；这里只做基础风险归类，最终执行仍由 BaBiQ HITL 链路裁决。
     */
    private boolean approvalRequired(String name) {
        return "exec_shell".equals(name)
                || "write_file".equals(name)
                || "apply_patch".equals(name)
                || (name != null && name.startsWith("mcp."));
    }

    /**
     * 给能力目录提供简洁风险等级，帮助后续按需装配策略排序。
     */
    private String riskLevel(String name) {
        if ("exec_shell".equals(name) || "apply_patch".equals(name)) {
            return "high";
        }
        if ("write_file".equals(name) || (name != null && name.startsWith("mcp."))) {
            return "medium";
        }
        return "low";
    }
}
