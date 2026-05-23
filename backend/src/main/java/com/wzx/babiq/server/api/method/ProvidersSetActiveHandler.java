package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * model/providers/set-active 方法处理器。
 *
 * <p>桌面端模型选择器调用该方法切换后端当前 active provider。真正可用的 provider
 * 来源仍是后端配置,桌面端只负责选择,不负责保存 API key 或动态新增 provider。</p>
 */
@Component
public class ProvidersSetActiveHandler implements JsonRpcMethodHandler {

    /** 模型 Provider 注册表，UI 切换模型时最终会更新这里的 active provider。 */
    private final ModelProviderRegistry registry;

    /**
     * 创建 active provider 切换处理器。
     *
     * @param registry 模型 Provider 注册中心
     */
    public ProvidersSetActiveHandler(ModelProviderRegistry registry) {
        this.registry = registry;
    }

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
     * 切换 active provider。
     *
     * @param params 请求参数,必须包含 providerId,可选 modelId
     * @param session 当前 WebSocket 会话,不依赖 session 状态
     * @return ok=true 以及切换后的 providerId/modelId
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String providerId = requiredText(params, "providerId");
        String requestedModelId = optionalText(params, "modelId");

        if (!ProviderSelectionPolicy.isSelectable(providerId)) {
            throw invalidParams(ProviderSelectionPolicy.unsupportedMessage(providerId));
        }

        ModelProviderConfig selectedProvider = providerConfig(providerId);
        validateRequestedModel(requestedModelId, selectedProvider);
        registry.setActive(providerId);

        return Map.of(
                "ok", true,
                "providerId", selectedProvider.id(),
                "modelId", selectedProvider.model()
        );
    }

    private ModelProviderConfig providerConfig(String providerId) {
        try {
            return registry.get(providerId);
        } catch (IllegalArgumentException exception) {
            throw invalidParams(exception.getMessage());
        }
    }

    private static void validateRequestedModel(String requestedModelId, ModelProviderConfig selectedProvider) {
        if (requestedModelId == null || requestedModelId.equals(selectedProvider.model())) {
            return;
        }
        throw invalidParams("provider [" + selectedProvider.id() + "] 不包含模型 [" + requestedModelId
                + "],当前可用模型: " + selectedProvider.model());
    }

    private static String requiredText(JsonNode params, String fieldName) {
        String value = optionalText(params, fieldName);
        if (value == null) {
            throw invalidParams("缺少必填参数: " + fieldName);
        }
        return value;
    }

    private static String optionalText(JsonNode params, String fieldName) {
        if (params == null || params.get(fieldName) == null || params.get(fieldName).isNull()) {
            return null;
        }
        String value = params.get(fieldName).asText("").trim();
        return value.isBlank() ? null : value;
    }

    private static JsonRpcException invalidParams(String message) {
        return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, message);
    }
}
