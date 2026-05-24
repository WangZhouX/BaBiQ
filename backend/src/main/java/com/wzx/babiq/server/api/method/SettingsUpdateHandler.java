package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.settings.AppSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * `settings/update` JSON-RPC handler。
 *
 * <p>handler 接受部分字段更新，并把校验和持久化交给 AppSettingsService。</p>
 */
@Component
public class SettingsUpdateHandler implements JsonRpcMethodHandler {

    /** 应用设置服务。 */
    private final AppSettingsService appSettingsService;
    /** JSON 转换器，保留给后续复杂设置字段扩展。 */
    private final ObjectMapper objectMapper;

    public SettingsUpdateHandler(AppSettingsService appSettingsService, ObjectMapper objectMapper) {
        this.appSettingsService = appSettingsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String method() {
        return "settings/update";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        try {
            AppSettingsService.AppSettingsUpdate update = new AppSettingsService.AppSettingsUpdate(
                    optionalText(params, "activeProviderId"),
                    optionalText(params, "sandboxMode"),
                    optionalText(params, "approvalPolicy"),
                    optionalText(params, "defaultCwd"));
            return SettingsGetHandler.payload(appSettingsService.update(update));
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }

    private String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }
}
