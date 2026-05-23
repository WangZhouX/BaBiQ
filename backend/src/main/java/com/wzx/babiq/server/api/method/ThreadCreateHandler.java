package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * thread/create 方法处理器。
 *
 * <p>该 handler 是 P1-1 的真实业务入口之一,用于根据客户端传入的 cwd 创建
 * 一个内存 Thread。后续 turn/start 必须带这个 threadId 才能创建 Turn。</p>
 */
@Component
public class ThreadCreateHandler implements JsonRpcMethodHandler {

    /** 会话服务，负责创建 thread 并保存它绑定的工作目录 cwd。 */
    private final ConversationService conversationService;

    /**
     * 创建 thread/create handler。
     *
     * @param conversationService 对话生命周期服务
     */
    public ThreadCreateHandler(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return thread/create
     */
    @Override
    public String method() {
        return "thread/create";
    }

    /**
     * 创建 Thread 并返回 threadId。
     *
     * @param params 必须包含 cwd 字段
     * @param session 当前 WebSocket 会话,本方法不直接使用
     * @return 包含 threadId 的响应对象
     * @throws JsonRpcException cwd 缺失或为空时抛 INVALID_PARAMS
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String cwd = requiredText(params, "cwd");
        Thread thread = conversationService.createThread(cwd);
        return Map.of("threadId", thread.id());
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
