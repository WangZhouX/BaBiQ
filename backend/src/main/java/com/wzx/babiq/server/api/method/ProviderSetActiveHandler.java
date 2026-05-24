package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * provider/set-active 新协议别名处理器。
 *
 * <p>P1 已经存在 `model/providers/set-active`，P2-3 把设置页协议统一到 `provider/*`。
 * 为了不复制切换逻辑，本类只做方法名别名，实际校验、持久化和返回结构全部委托给旧 handler。</p>
 */
@Component
public class ProviderSetActiveHandler implements JsonRpcMethodHandler {

    /** 旧协议处理器，里面已经实现 provider 存在性校验和 active-provider 持久化。 */
    private final ProvidersSetActiveHandler delegate;

    /**
     * 创建 provider/set-active 别名处理器。
     *
     * @param delegate 已存在的 model/providers/set-active 处理器
     */
    public ProviderSetActiveHandler(ProvidersSetActiveHandler delegate) {
        this.delegate = delegate;
    }

    /**
     * 返回 P2-3 新协议方法名。
     *
     * @return provider/set-active
     */
    @Override
    public String method() {
        return "provider/set-active";
    }

    /**
     * 委托旧 handler 处理 active provider 切换。
     *
     * @param params 请求参数，沿用 providerId/modelId
     * @param session 当前 WebSocket 会话
     * @return 旧 handler 返回的 ok/providerId/modelId
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return delegate.handle(params, session);
    }
}
