package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * approval/respond 方法占位处理器。
 *
 * <p>审批状态机会在 P1-3 接 HumanInTheLoopHook 后实现。P1-1 只保留 method
 * 和固定响应,避免协议层缺口阻塞桌面端联调。</p>
 */
@Component
public class ApprovalRespondHandler implements JsonRpcMethodHandler {

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
     * 返回 P1-1 占位响应。
     *
     * @param params 请求参数,本阶段不解释
     * @param session 当前 WebSocket 会话,本阶段不使用
     * @return ok=true 且 mock=true 的响应对象
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return Map.of("ok", true, "mock", true);
    }
}
