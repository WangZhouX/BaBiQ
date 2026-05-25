package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.settings.SandboxSettingsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * `sandbox/policy/set` JSON-RPC handler。
 *
 * <p>更新后的沙箱模式从下一轮 turn 开始生效，已经启动的 turn 使用启动时快照。</p>
 */
@Component
public class SandboxPolicySetHandler implements JsonRpcMethodHandler {

    /** 沙箱设置服务。 */
    private final SandboxSettingsService sandboxSettingsService;

    public SandboxPolicySetHandler(SandboxSettingsService sandboxSettingsService) {
        this.sandboxSettingsService = sandboxSettingsService;
    }

    @Override
    public String method() {
        return "sandbox/policy/set";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        try {
            return SandboxPolicyHandler.payload(SandboxMode.valueOf(
                    sandboxSettingsService.setMode(requiredText(params, "mode")).sandboxMode()));
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
