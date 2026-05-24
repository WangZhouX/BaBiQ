package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 记录工具调用次数的官方 ToolInterceptor 薄封装。
 */
@Component
public class ToolObservationInterceptor extends ToolInterceptor {

    /** 拦截器日志；工具调用持久化失败时只降级记录，不让观测链路打断 Agent 主流程。 */
    private static final Logger log = LoggerFactory.getLogger(ToolObservationInterceptor.class);

    /** 全局工具调用指标聚合器，和每轮 TurnObservationContext 的局部统计互相补充。 */
    private final BaBiQMetrics metrics;
    /** 可选工具调用持久化服务；单元测试旧构造器可为空，生产环境用于写入 bq_tool_calls。 */
    private final ToolCallPersistenceService toolCallPersistenceService;

    /**
     * 创建工具观测拦截器。
     */
    public ToolObservationInterceptor(BaBiQMetrics metrics) {
        this(metrics, null);
    }

    /**
     * 创建带持久化能力的工具观测拦截器。
     *
     * @param metrics 内存指标聚合器
     * @param toolCallPersistenceService 工具调用持久化服务；为空时只做 P1 内存指标
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ToolObservationInterceptor(BaBiQMetrics metrics, ToolCallPersistenceService toolCallPersistenceService) {
        this.metrics = metrics;
        this.toolCallPersistenceService = toolCallPersistenceService;
    }

    @Override
    public String getName() {
        return "babiq_tool_observation";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        // 先记录再执行，确保工具即使失败也计入调用次数。
        TurnObservationContext context = record(request);
        persistStartedIfPossible(request, context);
        try {
            ToolCallResponse response = handler.call(request);
            persistFinishedIfPossible(request, response);
            return response;
        } catch (RuntimeException exception) {
            persistFailedIfPossible(request, exception);
            throw exception;
        }
    }

    /**
     * 同时记录本轮上下文指标和全局内存指标。
     */
    private TurnObservationContext record(ToolCallRequest request) {
        Object candidate = request.getContext() == null
                ? null
                : request.getContext().get(TurnObservationContext.METADATA_KEY);
        if (candidate instanceof TurnObservationContext context) {
            // TurnObservationContext 用于本轮 turnSummary。
            context.recordToolCall(request.getToolName());
            metrics.recordToolCall(request.getToolName());
            return context;
        }
        // BaBiQMetrics 用于 P1 内存指标快照，P2 可接 Micrometer/Actuator。
        metrics.recordToolCall(request.getToolName());
        return null;
    }

    private void persistStartedIfPossible(ToolCallRequest request, TurnObservationContext context) {
        if (toolCallPersistenceService == null || context == null) {
            return;
        }
        try {
            toolCallPersistenceService.recordStarted(
                    request.getToolCallId(),
                    context.threadId(),
                    context.turnId(),
                    request.getToolName(),
                    request.getArguments(),
                    Instant.now());
        } catch (RuntimeException exception) {
            // 运行记录属于观测增强，不能反向影响工具执行；例如旧测试或内存 turn 没有先落库时会触发外键失败。
            log.warn("工具调用开始记录持久化失败，已降级为仅内存观测: toolCallId={}, toolName={}, reason={}",
                    request.getToolCallId(), request.getToolName(), exception.getMessage());
            log.debug("工具调用开始记录持久化失败详情", exception);
        }
    }

    private void persistFinishedIfPossible(ToolCallRequest request, ToolCallResponse response) {
        if (toolCallPersistenceService == null) {
            return;
        }
        String status = response.isError() ? deniedOrFailed(response.getResult()) : "completed";
        String resultPreview = response.isError() ? null : response.getResult();
        String errorMessage = response.isError() ? response.getResult() : null;
        try {
            toolCallPersistenceService.recordFinished(
                    request.getToolCallId(), status, resultPreview, errorMessage, Instant.now());
        } catch (RuntimeException exception) {
            // 完成态更新失败同样不能覆盖工具真实结果，否则用户会看到“工具成功但 turn 失败”的假错误。
            log.warn("工具调用完成记录持久化失败，已保留工具真实响应: toolCallId={}, status={}, reason={}",
                    request.getToolCallId(), status, exception.getMessage());
            log.debug("工具调用完成记录持久化失败详情", exception);
        }
    }

    private void persistFailedIfPossible(ToolCallRequest request, RuntimeException exception) {
        if (toolCallPersistenceService == null) {
            return;
        }
        try {
            toolCallPersistenceService.recordFinished(
                    request.getToolCallId(), "failed", null, exception.getMessage(), Instant.now());
        } catch (RuntimeException persistenceException) {
            // 这里正在处理工具异常，持久化异常只写日志，不能掩盖原始工具异常。
            log.warn("工具调用失败记录持久化失败，已保留原始工具异常: toolCallId={}, reason={}",
                    request.getToolCallId(), persistenceException.getMessage());
            log.debug("工具调用失败记录持久化失败详情", persistenceException);
        }
    }

    private String deniedOrFailed(String result) {
        String normalized = result == null ? "" : result.toLowerCase();
        return normalized.contains("denied")
                || normalized.contains("rejected")
                || normalized.contains("sandbox")
                ? "denied"
                : "failed";
    }
}
