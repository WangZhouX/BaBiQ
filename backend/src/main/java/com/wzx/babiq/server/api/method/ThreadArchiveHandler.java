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
 * thread/archive 方法处理器。
 *
 * <p>归档只是让默认最近列表隐藏会话，不删除数据库中的消息和运行记录。</p>
 */
@Component
public class ThreadArchiveHandler implements JsonRpcMethodHandler {

    /** 会话历史应用服务，负责归档规则和持久化更新。 */
    private final ConversationApplicationService conversationApplicationService;
    private final BusinessIdentityScopeService scopes;

    /**
     * 创建 thread/archive handler。
     *
     * @param conversationApplicationService 会话历史应用服务
     */
    public ThreadArchiveHandler(ConversationApplicationService conversationApplicationService) {
        this(conversationApplicationService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ThreadArchiveHandler(ConversationApplicationService conversationApplicationService,
                                BusinessIdentityScopeService scopes) {
        this.conversationApplicationService = conversationApplicationService;
        this.scopes = scopes;
    }

    @Override
    public String method() {
        return "thread/archive";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        try {
            return scopes == null ? conversationApplicationService.archiveThread(threadId)
                    : conversationApplicationService.archiveThread(threadId, scopes.resolve(session));
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "thread 不存在");
        }
    }

    private static String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
