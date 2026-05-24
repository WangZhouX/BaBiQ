package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.settings.AppSettings;
import com.wzx.babiq.server.settings.AppSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `settings/get` JSON-RPC handler。
 *
 * <p>读取本地设置快照，供桌面设置页初始化表单和上下文条。</p>
 */
@Component
public class SettingsGetHandler implements JsonRpcMethodHandler {

    /** 应用设置服务。 */
    private final AppSettingsService appSettingsService;

    public SettingsGetHandler(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    @Override
    public String method() {
        return "settings/get";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return payload(appSettingsService.get());
    }

    static Map<String, Object> payload(AppSettings settings) {
        return Map.of(
                "activeProviderId", settings.activeProviderId(),
                "sandboxMode", settings.sandboxMode(),
                "approvalPolicy", settings.approvalPolicy(),
                "defaultCwd", settings.defaultCwd()
        );
    }
}
