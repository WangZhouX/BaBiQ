package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.springframework.stereotype.Component;

/**
 * 记录工具调用次数的官方 ToolInterceptor 薄封装。
 */
@Component
public class ToolObservationInterceptor extends ToolInterceptor {

    private final BaBiQMetrics metrics;

    public ToolObservationInterceptor(BaBiQMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "babiq_tool_observation";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        record(request);
        return handler.call(request);
    }

    private void record(ToolCallRequest request) {
        Object candidate = request.getContext() == null
                ? null
                : request.getContext().get(TurnObservationContext.METADATA_KEY);
        if (candidate instanceof TurnObservationContext context) {
            context.recordToolCall(request.getToolName());
        }
        metrics.recordToolCall(request.getToolName());
    }
}
