package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.JsonRpcLogSupport;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * turn/start 方法处理器。
 *
 * <p>P1-3a 起该 handler 不再发 mock item 流，而是创建 Turn、同步返回 turnId，
 * 再把真实 AgentLoop 提交给 TurnExecutor 异步执行。它存在的原因是协议线程不能被
 * 模型调用或 HITL 中断阻塞。</p>
 */
@Component
public class TurnStartHandler implements JsonRpcMethodHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnStartHandler.class);

    /** 创建或读取 thread/turn 的内存会话服务，是 turn/start 的状态来源。 */
    private final ConversationService conversationService;
    /** 把 JSON-RPC params 转成强类型 TurnStartParams，减少手写字段解析。 */
    private final ObjectMapper objectMapper;
    /** 后台 turn 调度器，handler 快速返回后由它继续执行 AgentLoop。 */
    private final TurnExecutor turnExecutor;

    /**
     * 创建 turn/start handler。
     *
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param turnExecutor Agent 异步执行器
     */
    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            TurnExecutor turnExecutor) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.turnExecutor = turnExecutor;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return turn/start
     */
    @Override
    public String method() {
        return "turn/start";
    }

    /**
     * 创建 turn 并提交真实 AgentLoop。
     *
     * @param params 必须包含 threadId 与 input.text，可选 providerId
     * @param session 当前 WebSocket 会话
     * @return 包含 turnId 的响应对象
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        String userText = requiredInputText(params);
        String providerId = optionalText(params, "providerId");
        log.info("turn/start 收到请求: threadId={}, providerId={}, inputChars={}, inputPreview={}",
                threadId,
                providerId == null ? "<active-provider>" : providerId,
                userText.length(),
                JsonRpcLogSupport.preview(userText));
        Thread thread = conversationService.findThread(threadId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                        "threadId=" + threadId + " 不存在，无法创建 Turn"));
        Turn turn = conversationService.startTurn(threadId);
        turn.start();
        log.info("turn/start 已创建 Turn: threadId={}, turnId={}, cwd={}, providerId={}",
                threadId,
                turn.id(),
                thread.cwd(),
                providerId == null ? "<active-provider>" : providerId);

        ItemEmitter emitter = new ItemEmitter(session, objectMapper, threadId, turn.id());
        try {
            emitter.emitTurnStarted();
        } catch (Exception exception) {
            log.warn("发送 turn/started 失败 turnId={}", turn.id(), exception);
        }
        turnExecutor.submit(turn, userText, providerId, thread.cwd(), emitter);
        log.info("turn/start 已提交 AgentLoop: threadId={}, turnId={}, providerId={}",
                threadId,
                turn.id(),
                providerId == null ? "<active-provider>" : providerId);
        return Map.of("turnId", turn.id());
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

    private String requiredInputText(JsonNode params) {
        JsonNode textNode = params == null ? null : params.path("input").path("text");
        if (textNode == null || textNode.isMissingNode() || textNode.asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: input.text");
        }
        return textNode.asText();
    }
}
