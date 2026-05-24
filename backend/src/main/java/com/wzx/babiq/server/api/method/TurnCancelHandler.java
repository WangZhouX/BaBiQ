package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * turn/cancel 方法处理器。
 *
 * <p>该 handler 是 P1-1 的真实业务入口之一,用于把仍在执行或等待审批的 Turn
 * 标记为 CANCELED。状态合法性由 Turn 状态机负责。</p>
 */
@Component
public class TurnCancelHandler implements JsonRpcMethodHandler {

    /** 会话服务，用来找到 turn 并把它标记为 cancelled。 */
    private final ConversationService conversationService;
    /** 可选 turn 持久化服务，生产环境取消时同步 bq_turns。 */
    private final TurnPersistenceService turnPersistenceService;

    /**
     * 创建 turn/cancel handler。
     *
     * @param conversationService 对话生命周期服务
     */
    public TurnCancelHandler(ConversationService conversationService) {
        this(conversationService, null);
    }

    /**
     * 创建带持久化能力的 turn/cancel handler。
     *
     * @param conversationService 对话生命周期服务
     * @param turnPersistenceService turn 持久化服务；为空时只更新内存状态
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TurnCancelHandler(ConversationService conversationService, TurnPersistenceService turnPersistenceService) {
        this.conversationService = conversationService;
        this.turnPersistenceService = turnPersistenceService;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return turn/cancel
     */
    @Override
    public String method() {
        return "turn/cancel";
    }

    /**
     * 取消指定 Turn。
     *
     * @param params 必须包含 turnId 字段
     * @param session 当前 WebSocket 会话,本方法不直接使用
     * @return ok=true 的响应对象
     * @throws JsonRpcException turnId 缺失、不存在或状态不允许取消时抛出
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String turnId = requiredText(params, "turnId");
        Turn turn = conversationService.findTurn(turnId)
                .orElseThrow(() -> new JsonRpcException(
                        JsonRpcErrorCode.INVALID_PARAMS,
                        "turnId=" + turnId + " 不存在,无法取消"));

        try {
            turn.cancel();
        } catch (IllegalStateException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.SERVER_ERROR, exception.getMessage());
        }
        if (turnPersistenceService != null) {
            turnPersistenceService.markCanceled(turnId, "CANCELED", "user_cancelled");
        }
        return Map.of("ok", true);
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
