package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.settings.AppSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `approval/policy` JSON-RPC handler。
 *
 * <p>返回当前默认审批策略，供设置页和未来上下文条使用。</p>
 */
@Component
public class ApprovalPolicyGetHandler implements JsonRpcMethodHandler {

    /** 应用设置服务。 */
    private final AppSettingsService appSettingsService;

    public ApprovalPolicyGetHandler(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    @Override
    public String method() {
        return "approval/policy";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return Map.of("approvalPolicy", appSettingsService.get().approvalPolicy());
    }
}
