package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * model/providers/set-active 方法占位处理器。
 *
 * <p>P1-1 还没有真实模型 provider,因此该方法只确认协议路径存在。P1-2 会用
 * ModelProviderRegistry 替换这里的 mock 逻辑。</p>
 */
@Component
public class ProvidersSetActiveHandler implements JsonRpcMethodHandler {

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return model/providers/set-active
     */
    @Override
    public String method() {
        return "model/providers/set-active";
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
