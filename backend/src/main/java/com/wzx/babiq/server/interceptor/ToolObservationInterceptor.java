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

    /** 全局工具调用指标聚合器，和每轮 TurnObservationContext 的局部统计互相补充。 */
    private final BaBiQMetrics metrics;

    /**
     * 创建工具观测拦截器。
     */
    public ToolObservationInterceptor(BaBiQMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public String getName() {
        return "babiq_tool_observation";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        // 先记录再执行，确保工具即使失败也计入调用次数。
        record(request);
        return handler.call(request);
    }

    /**
     * 同时记录本轮上下文指标和全局内存指标。
     */
    private void record(ToolCallRequest request) {
        Object candidate = request.getContext() == null
                ? null
                : request.getContext().get(TurnObservationContext.METADATA_KEY);
        if (candidate instanceof TurnObservationContext context) {
            // TurnObservationContext 用于本轮 turnSummary。
            context.recordToolCall(request.getToolName());
        }
        // BaBiQMetrics 用于 P1 内存指标快照，P2 可接 Micrometer/Actuator。
        metrics.recordToolCall(request.getToolName());
    }
}
