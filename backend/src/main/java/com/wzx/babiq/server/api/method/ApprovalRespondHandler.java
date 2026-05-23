package com.wzx.babiq.server.api.method;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.observability.BaBiQMetrics;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * approval/respond 方法处理器。
 *
 * <p>P1-3a 使用 SAA 原生 HITL interrupt/resume。该 handler 不手写审批状态机，
 * 只把用户决策转成 InterruptionMetadata.ToolFeedback，再通过 RunnableConfig.resume
 * 所需的数据提交给 TurnExecutor 续跑。</p>
 */
@Component
public class ApprovalRespondHandler implements JsonRpcMethodHandler {

    private final PendingApprovals pendingApprovals;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final TurnExecutor turnExecutor;
    private final BaBiQMetrics metrics;

    /**
     * 创建 approval/respond handler。
     *
     * @param pendingApprovals 待审批元数据缓存
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     * @param metrics P1 可观测指标聚合器
     */
    public ApprovalRespondHandler(PendingApprovals pendingApprovals,
                                  ConversationService conversationService,
                                  ObjectMapper objectMapper,
                                  TurnExecutor turnExecutor,
                                  BaBiQMetrics metrics) {
        this.pendingApprovals = pendingApprovals;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.turnExecutor = turnExecutor;
        this.metrics = metrics;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return approval/respond
     */
    @Override
    public String method() {
        return "approval/respond";
    }

    /**
     * 接收用户审批并提交 HITL resume。
     *
     * @param params 必须包含 threadId、turnId、decision；edit 时可带 editedArgs
     * @param session 当前 WebSocket 会话
     * @return delivered=true
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        String turnId = requiredText(params, "turnId");
        String decision = requiredText(params, "decision");
        String editedArgs = optionalText(params, "editedArgs");
        InterruptionMetadata original = pendingApprovals.take(threadId);
        if (original == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "没有待审批请求: " + threadId);
        }

        Turn turn = conversationService.findTurn(turnId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "turn 不存在: " + turnId));
        Thread thread = conversationService.findThread(threadId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "thread 不存在: " + threadId));
        turn.resume();
        ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turnId);
        InterruptionMetadata feedback = buildFeedback(original, decision, editedArgs);
        metrics.recordApprovalDecision(canonicalDecision(decision));
        turnExecutor.submitResume(turn, feedback, thread.cwd(), emitter);
        return Map.of("delivered", true);
    }

    /**
     * 根据用户决策构造 SAA HITL 反馈元数据。
     *
     * @param original 原始中断元数据
     * @param decision 用户决策 approve/deny/edit
     * @param editedArgs edit 模式下的新参数
     * @return 可用于 RunnableConfig.addHumanFeedback 的元数据
     */
    public InterruptionMetadata buildFeedback(InterruptionMetadata original, String decision, String editedArgs) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder(original);
        builder.toolFeedbacks(java.util.List.of());
        for (InterruptionMetadata.ToolFeedback feedback : original.toolFeedbacks()) {
            builder.addToolFeedback(convertFeedback(feedback, decision, editedArgs));
        }
        return builder.build();
    }

    private InterruptionMetadata.ToolFeedback convertFeedback(
            InterruptionMetadata.ToolFeedback feedback, String decision, String editedArgs) {
        InterruptionMetadata.ToolFeedback.Builder builder = InterruptionMetadata.ToolFeedback.builder(feedback);
        return switch (decision.toLowerCase()) {
            case "approve", "approved" -> builder
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                    .build();
            case "deny", "denied", "reject", "rejected" -> builder
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                    .description("用户拒绝执行")
                    .build();
            case "edit", "edited" -> builder
                    .result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED)
                    .arguments(editedArgs == null ? feedback.getArguments() : editedArgs)
                    .build();
            default -> throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "未知审批决策: " + decision);
        };
    }

    private String canonicalDecision(String decision) {
        return switch (decision.toLowerCase()) {
            case "approve", "approved" -> "approved";
            case "deny", "denied", "reject", "rejected" -> "denied";
            case "edit", "edited" -> "edited";
            default -> throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "未知审批决策: " + decision);
        };
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }

    private String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }
}
