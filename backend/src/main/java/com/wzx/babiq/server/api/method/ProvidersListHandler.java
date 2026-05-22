package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

/**
 * model/providers/list 方法占位处理器。
 *
 * <p>真实 provider registry 属于 P1-2。本阶段返回固定 mock provider,确保
 * method 名和响应形态已经在协议层存在。</p>
 */
@Component
public class ProvidersListHandler implements JsonRpcMethodHandler {

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return model/providers/list
     */
    @Override
    public String method() {
        return "model/providers/list";
    }

    /**
     * 返回 P1-1 固定 provider 列表。
     *
     * @param params 请求参数,本阶段不解释
     * @param session 当前 WebSocket 会话,本阶段不使用
     * @return 包含 providers 数组的响应对象
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return Map.of("providers", List.of(
                Map.of("id", "mock-provider", "label", "Mock (P1-1 placeholder)")
        ));
    }
}
