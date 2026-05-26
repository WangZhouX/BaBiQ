package com.wzx.babiq.server.context.model;

import java.util.List;

/**
 * 当前 turn 的能力目录。
 *
 * <p>P3-1 先定义最小工具摘要列表；后续 P3-5 可以把 Skill、MCP server、插件能力继续分组扩展，
 * 但仍保持“目录摘要”和“真实 tool schema”分离。</p>
 *
 * @param toolSummaries 当前候选工具或能力摘要，允许为空列表
 */
public record CapabilityCatalog(List<CapabilityDescriptor> toolSummaries) {

    /**
     * 归一化空目录，避免调用方在组装 envelope 时处理 null。
     */
    public CapabilityCatalog {
        toolSummaries = toolSummaries == null ? List.of() : List.copyOf(toolSummaries);
    }
}
