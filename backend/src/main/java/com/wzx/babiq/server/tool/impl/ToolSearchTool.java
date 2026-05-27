package com.wzx.babiq.server.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.capability.CapabilityDescriptor;
import com.wzx.babiq.server.capability.CapabilitySearchRequest;
import com.wzx.babiq.server.capability.CapabilitySearchResult;
import com.wzx.babiq.server.capability.CapabilitySearchService;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.tool.Tool;
import com.wzx.babiq.server.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Codex 风格的能力搜索工具。
 *
 * <p>该工具只返回延迟能力的 metadata，不直接执行命中的能力。它仍然作为普通 BaBiQ Tool
 * 注册进 ToolRegistry，所以调用次数、审批策略、Spotlighting 和工具运行记录都沿用现有链路。</p>
 */
@Component
public class ToolSearchTool implements Tool {

    /** 默认返回数量，和 Codex 的 tool_search 默认值保持接近。 */
    private static final int DEFAULT_LIMIT = 8;

    /** 能力搜索服务。 */
    private final CapabilitySearchService searchService;
    /** JSON mapper，用于给模型返回稳定结构。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建 tool_search 工具。
     */
    public ToolSearchTool(CapabilitySearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return "tool_search";
    }

    /**
     * 搜索延迟能力。
     *
     * @param query 搜索词
     * @param limit 最大返回数量；为空或小于 1 时使用默认值
     * @param toolContext Spring AI 工具上下文，携带 thread/turn 观测信息
     * @return JSON 格式搜索结果
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "tool_search",
            description = "搜索按需加载的工具、MCP 能力和本地 Skill metadata",
            resultConverter = DefaultToolCallResultConverter.class)
    public ToolResult search(@ToolParam(description = "搜索 query") String query,
                             @ToolParam(description = "最大返回数量") Integer limit,
                             ToolContext toolContext) {
        if (query == null || query.isBlank()) {
            return ToolResult.failure("query must not be empty");
        }
        TurnObservationContext context = observationContext(toolContext);
        CapabilitySearchResult result = searchService.search(new CapabilitySearchRequest(
                context == null ? null : context.threadId(),
                context == null ? null : context.turnId(),
                query,
                limit == null || limit <= 0 ? DEFAULT_LIMIT : limit,
                true));
        return ToolResult.ok(writeResult(result));
    }

    /**
     * 兼容直接单元测试调用。
     */
    public ToolResult search(String query, Integer limit) {
        return search(query, limit, null);
    }

    private TurnObservationContext observationContext(ToolContext toolContext) {
        Object candidate = toolContext == null ? null : toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return candidate instanceof TurnObservationContext context ? context : null;
    }

    private String writeResult(CapabilitySearchResult result) {
        try {
            return objectMapper.writeValueAsString(new ToolSearchResponse(
                    result.strategy(),
                    result.results().stream().map(ToolSearchCapability::from).toList()));
        } catch (Exception exception) {
            throw new IllegalStateException("tool_search 结果序列化失败", exception);
        }
    }

    /**
     * 返回给模型的轻量搜索结果。
     *
     * @param strategy 搜索策略
     * @param capabilities 命中的能力列表
     */
    private record ToolSearchResponse(String strategy, List<ToolSearchCapability> capabilities) {
    }

    /**
     * 单个命中能力的模型可见字段。
     *
     * @param capabilityId 能力 id
     * @param type 能力类型
     * @param name 工具或 skill 名称
     * @param description 能力说明
     * @param exposureMode 暴露模式
     */
    private record ToolSearchCapability(
            String capabilityId,
            String type,
            String name,
            String description,
            String exposureMode
    ) {
        private static ToolSearchCapability from(CapabilityDescriptor descriptor) {
            return new ToolSearchCapability(
                    descriptor.capabilityId(),
                    descriptor.type().name(),
                    descriptor.name(),
                    descriptor.description(),
                    descriptor.exposureMode().name());
        }
    }
}
