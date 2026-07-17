package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.application.tool.ApplicationToolInvocationContext;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.items.CommandExecutionItem;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 记录工具调用次数的官方 ToolInterceptor 薄封装。
 */
@Component
public class ToolObservationInterceptor extends ToolInterceptor {

    /** 拦截器日志；工具调用持久化失败时只降级记录，不让观测链路打断 Agent 主流程。 */
    private static final Logger log = LoggerFactory.getLogger(ToolObservationInterceptor.class);
    /** 工具入参预览解析器，只抽取适合展示的字段，避免把 write_file 的正文直接铺到聊天流。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 工具结果在聊天流里的最大预览长度；完整结果仍保存在工具记录和模型上下文链路中。 */
    private static final int TOOL_DETAIL_PREVIEW_LIMIT = 1200;
    /** 工具入参在聊天流里的最大预览长度，用于 JSON 无法结构化解析时兜底。 */
    private static final int TOOL_ARGUMENT_PREVIEW_LIMIT = 400;
    /** 应用动作审计标识符的最大长度，避免敏感正文伪装成标识符写入运行记录。 */
    private static final int APPLICATION_ACTION_IDENTIFIER_LIMIT = 256;

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
        long startedNanos = System.nanoTime();
        try (ApplicationToolInvocationContext.Scope ignored = installApplicationContext(request, context)) {
            ToolCallResponse response = handler.call(request);
            persistFinishedIfPossible(request, response);
            emitToolDetailIfPossible(request, response, elapsedMillis(startedNanos));
            return response;
        } catch (RuntimeException exception) {
            persistFailedIfPossible(request, exception);
            emitFailedToolDetailIfPossible(request, exception, elapsedMillis(startedNanos));
            throw exception;
        }
    }

    /** 仅给业务桌面动作工具安装关联上下文；其他工具保持原来的调用环境。 */
    private ApplicationToolInvocationContext.Scope installApplicationContext(
            ToolCallRequest request,
            TurnObservationContext context) {
        if (!"application_action".equals(request.getToolName()) || context == null) {
            return null;
        }
        return ApplicationToolInvocationContext.install(new ApplicationToolInvocationContext.Invocation(
                request.getToolCallId(),
                context.threadId(),
                context.turnId(),
                context.businessIdentityScope()));
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
                    persistenceArguments(request),
                    agentName(request),
                    parentAgentName(request),
                    delegationId(request),
                    context.businessIdentityScope(),
                    Instant.now());
        } catch (RuntimeException exception) {
            // 运行记录属于观测增强，不能反向影响工具执行；例如旧测试或内存 turn 没有先落库时会触发外键失败。
            log.warn("工具调用开始记录持久化失败，已降级为仅内存观测: toolCallId={}, toolName={}, reason={}",
                    request.getToolCallId(), request.getToolName(), exception.getMessage());
            log.debug("工具调用开始记录持久化失败详情", exception);
        }
    }

    private String persistenceArguments(ToolCallRequest request) {
        if (!"application_action".equals(request.getToolName())) {
            return request.getArguments();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(request.getArguments());
            var safe = OBJECT_MAPPER.createObjectNode();
            copyBoundedText(root, safe, "actionId");
            copyPositiveInt(root, safe, "actionVersion");
            copyBoundedText(root, safe, "pageId");
            copyPositiveLong(root, safe, "contextRevision");
            safe.put("input", "[REDACTED]");
            return OBJECT_MAPPER.writeValueAsString(safe);
        } catch (Exception ignored) {
            return "{\"input\":\"[REDACTED]\"}";
        }
    }

    private void copyBoundedText(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isTextual()) {
            return;
        }
        String text = value.textValue();
        if (text != null && !text.isBlank() && text.length() <= APPLICATION_ACTION_IDENTIFIER_LIMIT) {
            target.put(field, text);
        }
    }

    private void copyPositiveInt(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0) {
            target.put(field, value.intValue());
        }
    }

    private void copyPositiveLong(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) {
            target.put(field, value.longValue());
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

    /**
     * 把每次工具调用同步发成聊天流里的轻量明细卡片。
     *
     * <p>P2-4 已经把完整工具调用写入 bq_tool_calls，TurnSummary 也会展示总次数；
     * 这里补的是用户正在看的主聊天流：工具明细必须先于本轮统计出现，才能和原型中的
     * “工具调用过程 -> 本轮运行反馈”顺序一致。</p>
     */
    private void emitToolDetailIfPossible(ToolCallRequest request, ToolCallResponse response, long durationMs) {
        SubAgentDelegationContext delegation = delegationContext(request);
        if (delegation != null) {
            delegation.recordChildToolCall(request.getToolName());
            return;
        }
        if (BuiltInSubAgents.EXPLORER_NAME.equals(request.getToolName())) {
            return;
        }
        if ("application_action".equals(request.getToolName())) {
            return;
        }
        ItemEmitter emitter = itemEmitter(request);
        if (emitter == null) {
            return;
        }
        String status = response.isError() ? deniedOrFailed(response.getResult()) : "completed";
        CommandExecutionItem item = new CommandExecutionItem(
                newItemId(),
                "commandExecution",
                displayCommand(request),
                status,
                null,
                response.isError() ? null : preview(response.getResult(), TOOL_DETAIL_PREVIEW_LIMIT),
                response.isError() ? preview(response.getResult(), TOOL_DETAIL_PREVIEW_LIMIT) : null,
                durationMs);
        emitCommandExecution(emitter, item, request);
    }

    /**
     * 工具以异常方式失败时也发明细卡片，避免用户只看到 turn 失败而看不到是哪次工具调用出错。
     */
    private void emitFailedToolDetailIfPossible(ToolCallRequest request, RuntimeException exception, long durationMs) {
        SubAgentDelegationContext delegation = delegationContext(request);
        if (delegation != null) {
            delegation.recordChildToolCall(request.getToolName());
            return;
        }
        if (BuiltInSubAgents.EXPLORER_NAME.equals(request.getToolName())) {
            return;
        }
        if ("application_action".equals(request.getToolName())) {
            return;
        }
        ItemEmitter emitter = itemEmitter(request);
        if (emitter == null) {
            return;
        }
        CommandExecutionItem item = new CommandExecutionItem(
                newItemId(),
                "commandExecution",
                displayCommand(request),
                "failed",
                null,
                null,
                preview(exception.getMessage(), TOOL_DETAIL_PREVIEW_LIMIT),
                durationMs);
        emitCommandExecution(emitter, item, request);
    }

    private void emitCommandExecution(ItemEmitter emitter, CommandExecutionItem item, ToolCallRequest request) {
        try {
            emitter.emitCommandExecution(item);
        } catch (Exception exception) {
            // UI 明细属于可观测增强，发送失败不能反向影响工具执行和模型续轮。
            log.warn("发送工具调用明细失败，已保留工具真实结果: toolCallId={}, toolName={}, reason={}",
                    request.getToolCallId(), request.getToolName(), exception.getMessage());
            log.debug("发送工具调用明细失败详情", exception);
        }
    }

    private ItemEmitter itemEmitter(ToolCallRequest request) {
        Object candidate = request.getContext() == null
                ? null
                : request.getContext().get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        if (candidate instanceof ItemEmitter emitter) {
            return emitter;
        }
        return null;
    }

    private SubAgentDelegationContext delegationContext(ToolCallRequest request) {
        Object candidate = request.getContext() == null
                ? null
                : request.getContext().get(SubAgentDelegationContext.METADATA_KEY);
        return candidate instanceof SubAgentDelegationContext delegation ? delegation : null;
    }

    private String agentName(ToolCallRequest request) {
        SubAgentDelegationContext delegation = delegationContext(request);
        return delegation == null ? BuiltInSubAgents.MAIN_AGENT_NAME : delegation.childAgent();
    }

    private String parentAgentName(ToolCallRequest request) {
        SubAgentDelegationContext delegation = delegationContext(request);
        return delegation == null ? null : delegation.parentAgent();
    }

    private String delegationId(ToolCallRequest request) {
        SubAgentDelegationContext delegation = delegationContext(request);
        return delegation == null ? null : delegation.delegationId();
    }

    /**
     * 生成聊天流展示用的工具标题。
     *
     * <p>这里优先抽取 path、command、query 等短字段；如果直接展示完整 arguments，
     * write_file 的正文会把聊天流撑得很长，也可能把用户真正关心的工具动作淹没。</p>
     */
    private String displayCommand(ToolCallRequest request) {
        String conciseArguments = conciseArguments(request.getArguments());
        if (conciseArguments.isBlank()) {
            return request.getToolName();
        }
        return request.getToolName() + " " + conciseArguments;
    }

    private String conciseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(arguments);
            if (root == null || !root.isObject()) {
                return preview(arguments, TOOL_ARGUMENT_PREVIEW_LIMIT);
            }
            List<String> parts = new ArrayList<>();
            appendDisplayField(parts, root, "path");
            appendDisplayField(parts, root, "command");
            appendDisplayField(parts, root, "query");
            appendDisplayField(parts, root, "pattern");
            appendDisplayField(parts, root, "cwd");
            appendDisplayField(parts, root, "url");
            return String.join(" ", parts);
        } catch (Exception ignored) {
            return preview(arguments, TOOL_ARGUMENT_PREVIEW_LIMIT);
        }
    }

    private void appendDisplayField(List<String> parts, JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return;
        }
        parts.add(fieldName + "=" + preview(value.asText(), 240));
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private String preview(String value, int limit) {
        if (value == null) {
            return null;
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n...[truncated]";
    }

    private String newItemId() {
        return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
