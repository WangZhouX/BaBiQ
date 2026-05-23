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
            return response;
        }

        String wrapped = spotlighter.wrapToolResult(
                request.getToolName(), extractPath(request.getArguments()), response.getResult());
        return new ToolCallResponse(
                wrapped,
                response.getToolName(),
                response.getToolCallId(),
                response.getStatus(),
                response.getMetadata());
    }

    private boolean isAlreadyWrapped(String result) {
        return result != null && result.stripLeading().startsWith("<untrusted-data");
    }

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

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
