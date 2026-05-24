package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.settings.ProviderSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * `provider/update` JSON-RPC handler。
 *
 * <p>更新时 API Key 可以为空，表示沿用原 secretRef；响应仍然只返回 hasApiKey。</p>
 */
@Component
public class ProviderUpdateHandler implements JsonRpcMethodHandler {

    /** 复用 create handler 的草稿解析逻辑。 */
    private final ProviderCreateHandler draftParser;
    /** Provider 设置服务。 */
    private final ProviderSettingsService providerSettingsService;

    public ProviderUpdateHandler(ProviderSettingsService providerSettingsService, ObjectMapper objectMapper) {
        this.providerSettingsService = providerSettingsService;
        this.draftParser = new ProviderCreateHandler(providerSettingsService, objectMapper);
    }

    @Override
    public String method() {
        return "provider/update";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        try {
            return ProviderPayloadMapper.toPayload(providerSettingsService.update(draftParser.draftFrom(params, false)));
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }
}
