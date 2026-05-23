package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.model.ModelMetadata;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

/**
 * model/providers/list 方法处理器。
 *
 * <p>桌面端通过该方法读取后端 application.yml 中已经配置好的 provider。这里
 * 只输出 id、展示名、模型名、上下文窗口等非敏感字段,绝不把 api-key 回传给 UI。</p>
 */
@Component
public class ProvidersListHandler implements JsonRpcMethodHandler {

    private final ModelProviderRegistry registry;

    /**
     * 创建 provider 列表处理器。
     *
     * @param registry 模型 Provider 注册中心
     */
    public ProvidersListHandler(ModelProviderRegistry registry) {
        this.registry = registry;
    }

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
     * 返回桌面端可选择的 provider 列表。
     *
     * @param params 请求参数,本阶段不解释
     * @param session 当前 WebSocket 会话,不依赖 session 状态
     * @return 包含 providers 数组的响应对象
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String activeProviderId = registry.active().id();
        List<Map<String, Object>> providers = registry.list().stream()
                .filter(ProviderSelectionPolicy::isSelectable)
                .map(providerConfig -> providerPayload(providerConfig, activeProviderId))
                .toList();
        return Map.of("providers", providers);
    }

    private static Map<String, Object> providerPayload(ModelProviderConfig providerConfig, String activeProviderId) {
        boolean active = providerConfig.id().equals(activeProviderId);
        return Map.of(
                "id", providerConfig.id(),
                "label", providerConfig.displayName(),
                "type", providerConfig.type().name(),
                "active", active,
                "contextWindow", ModelMetadata.contextWindowOf(providerConfig.model()),
                "models", List.of(modelPayload(providerConfig, active))
        );
    }

    private static Map<String, Object> modelPayload(ModelProviderConfig providerConfig, boolean active) {
        return Map.of(
                "id", providerConfig.model(),
                "label", providerConfig.model(),
                "active", active,
                "contextWindow", ModelMetadata.contextWindowOf(providerConfig.model())
        );
    }
}
