package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * turn/start 方法处理器。
 *
 * <p>P1-1 的核心路径:创建 Turn、同步返回 turnId,随后异步发出一段 mock item
 * 流。这里刻意不接真实 LLM,只验证 WebSocket + JSON-RPC + item 协议是否贯通。</p>
 */
@Component
public class TurnStartHandler implements JsonRpcMethodHandler {

    private static final Logger log = LoggerFactory.getLogger(TurnStartHandler.class);

    private static final int ITEM_ID_RANDOM_LENGTH = 8;

    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final String mockAgentText;

    /**
     * 创建 turn/start handler。
     *
     * @param conversationService 对话生命周期服务
     * @param objectMapper JSON 序列化器
     * @param mockAgentText P1-1 mock Agent 文本
     */
    public TurnStartHandler(
            ConversationService conversationService,
            ObjectMapper objectMapper,
            @Value("${babiq.protocol.mock-agent-text:hello from babiq}") String mockAgentText) {
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.mockAgentText = mockAgentText;
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
     * 创建 Turn 并异步发出 P1-1 mock 事件流。
     *
     * @param params 必须包含 threadId 和 input.text
     * @param session 当前 WebSocket 会话,用于异步 notification
     * @return 包含 turnId 的响应对象
     * @throws JsonRpcException 参数缺失或 threadId 不存在时抛出
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        String userText = requiredInputText(params);

        Turn turn;
        try {
            turn = conversationService.startTurn(threadId);
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
        turn.start();

        ItemEmitter itemEmitter = new ItemEmitter(session, objectMapper, threadId, turn.id());
        // P1-1 只跑极短 mock 流,暂用默认异步执行器;P1-2 接真实模型时再替换专用 Executor。
        CompletableFuture.runAsync(() -> runMockStream(itemEmitter, turn, userText));
        return Map.of("turnId", turn.id());
    }

    private void runMockStream(ItemEmitter itemEmitter, Turn turn, String userText) {
        try {
            itemEmitter.emitTurnStarted();
            itemEmitter.emitItemAdded(UserMessageItem.of(newItemId(), userText));
            itemEmitter.emitItemAdded(AgentMessageItem.full(newItemId(), mockAgentText));
            turn.complete();
            itemEmitter.emitTurnCompleted("completed");
        } catch (Exception exception) {
            log.error("P1-1 mock item 流执行失败: turnId={}", turn.id(), exception);
            markTurnFailedIfPossible(turn, exception);
            emitTurnFailedIfPossible(itemEmitter, turn, exception);
        }
    }

    private void markTurnFailedIfPossible(Turn turn, Exception exception) {
        if (turn.status().isTerminal()) {
            return;
        }
        turn.fail(exception.getMessage());
    }

    private void emitTurnFailedIfPossible(ItemEmitter itemEmitter, Turn turn, Exception exception) {
        try {
            itemEmitter.emitTurnFailed(exception.getMessage());
        } catch (Exception sendException) {
            log.error("发送 turn/failed 也失败: turnId={}", turn.id(), sendException);
        }
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
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

    private String newItemId() {
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, ITEM_ID_RANDOM_LENGTH);
        return "it_" + randomPart;
    }
}
