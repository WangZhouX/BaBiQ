package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * thread/list 方法处理器。
 *
 * <p>该 handler 只做 JSON-RPC 参数读取和默认值处理，实际查询交给 ConversationApplicationService。</p>
 */
@Component
public class ThreadListHandler implements JsonRpcMethodHandler {

    /** 最近会话应用服务，负责真正的持久化查询和 DTO 组装。 */
    private final ConversationApplicationService conversationApplicationService;

    /**
     * 创建 thread/list handler。
     *
     * @param conversationApplicationService 会话历史应用服务
     */
    public ThreadListHandler(ConversationApplicationService conversationApplicationService) {
        this.conversationApplicationService = conversationApplicationService;
    }

    @Override
    public String method() {
        return "thread/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String cwd = optionalText(params, "cwd");
        boolean includeArchived = params != null
                && params.hasNonNull("includeArchived")
                && params.get("includeArchived").asBoolean(false);
        int limit = clippedLimit(params, "limit", 30, 100);
        String cursor = optionalText(params, "cursor");
        return conversationApplicationService.listThreads(cwd, includeArchived, limit, cursor);
    }

    private static int clippedLimit(JsonNode params, String fieldName, int defaultValue, int maximum) {
        if (params == null || !params.hasNonNull(fieldName)) {
            return defaultValue;
        }
        return Math.max(1, Math.min(params.get(fieldName).asInt(defaultValue), maximum));
    }

    private static String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }
}
