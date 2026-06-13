package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.conversation.ConversationApplicationService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * runtime/item/remove JSON-RPC handler。
 *
 * <p>用于隐藏右侧运行详情里的终态运行卡片，例如 team 或 orchestration item。</p>
 */
@Component
public class RuntimeItemRemoveHandler implements JsonRpcMethodHandler {

    /** 会话历史应用服务，负责校验 item 类型并执行软移除。 */
    private final ConversationApplicationService service;

    public RuntimeItemRemoveHandler(ConversationApplicationService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "runtime/item/remove";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String itemId = ContextStatusHandler.requiredText(params, "itemId");
        String type = ContextStatusHandler.requiredText(params, "type");
        return service.removeRuntimeItem(itemId, type);
    }
}
