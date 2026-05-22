package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * turn/interrupt 方法占位处理器。
 *
 * <p>P1-1 只打通协议层,真实中断会在后续接入 Agent Loop 后实现。当前返回
 * mock=true,让桌面端可以提前联调按钮和响应结构。</p>
 */
@Component
public class TurnInterruptHandler implements JsonRpcMethodHandler {

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return turn/interrupt
     */
    @Override
    public String method() {
        return "turn/interrupt";
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
