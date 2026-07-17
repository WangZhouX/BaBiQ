package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * thread/load 方法处理器。
 *
 * <p>桌面端点击最近会话时调用该方法恢复历史消息。handler 保持薄层，避免协议解析和数据库查询耦合。</p>
 */
@Component
public class ThreadLoadHandler implements JsonRpcMethodHandler {

    /** 会话历史应用服务，负责从 SQLite 读取 thread 和 item。 */
    private final ConversationApplicationService conversationApplicationService;
    private final BusinessIdentityScopeService scopes;

    /**
     * 创建 thread/load handler。
     *
     * @param conversationApplicationService 会话历史应用服务
     */
    public ThreadLoadHandler(ConversationApplicationService conversationApplicationService) {
        this(conversationApplicationService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ThreadLoadHandler(ConversationApplicationService conversationApplicationService,
                             BusinessIdentityScopeService scopes) {
        this.conversationApplicationService = conversationApplicationService;
        this.scopes = scopes;
    }

    @Override
    public String method() {
        return "thread/load";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        int limit = clippedLimit(params, "limit", 200, 500);
        String beforeItemId = optionalText(params, "beforeItemId");
        try {
            return scopes == null
                    ? conversationApplicationService.loadThread(threadId, limit, beforeItemId)
                    : conversationApplicationService.loadThread(threadId, limit, beforeItemId, scopes.resolve(session));
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "thread 不存在");
        }
    }

    private static String requiredText(JsonNode params, String fieldName) {
        String value = optionalText(params, fieldName);
        if (value == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return value;
    }

    private static String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }

    private static int clippedLimit(JsonNode params, String fieldName, int defaultValue, int maximum) {
        if (params == null || !params.hasNonNull(fieldName)) {
            return defaultValue;
        }
        return Math.max(1, Math.min(params.get(fieldName).asInt(defaultValue), maximum));
    }
}
