package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.security.Spotlighter;
import org.springframework.stereotype.Component;

/**
 * 基于 Spring AI Alibaba 官方 ToolInterceptor 的工具结果安全标注器。
 */
@Component
public class SpotlightingToolInterceptor extends ToolInterceptor {

    private final Spotlighter spotlighter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建工具结果标注拦截器。
     */
    public SpotlightingToolInterceptor(Spotlighter spotlighter) {
        this.spotlighter = spotlighter;
    }

    @Override
    public String getName() {
        return "babiq_spotlighting";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        ToolCallResponse response = handler.call(request);
        if (response.isError() || isAlreadyWrapped(response.getResult())) {
            // 错误结果或已经标注过的结果不重复包裹，避免模型看到嵌套标签。
            return response;
        }

        // 工具输出默认来自不可信文件/命令，包上 untrusted-data 标签后再交给模型。
        String wrapped = spotlighter.wrapToolResult(
                request.getToolName(), extractPath(request.getArguments()), response.getResult());
        return new ToolCallResponse(
                wrapped,
                response.getToolName(),
                response.getToolCallId(),
                response.getStatus(),
                response.getMetadata());
    }

    /**
     * 判断工具结果是否已经被 spotlighting 包裹。
     */
    private boolean isAlreadyWrapped(String result) {
        return result != null && result.stripLeading().startsWith("<untrusted-data");
    }

    /**
     * 尝试从工具参数中提取 path/file 字段，用于标注数据来源。
     */
    private String extractPath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(arguments);
            String path = text(node, "path");
            return path == null ? text(node, "file") : path;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 读取 JSON 字符串字段，空值统一当作 null。
     */
    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
