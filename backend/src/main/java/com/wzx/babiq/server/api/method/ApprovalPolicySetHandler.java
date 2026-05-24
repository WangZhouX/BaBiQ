package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.settings.ApprovalPolicyService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * `approval/policy/set` JSON-RPC handler。
 *
 * <p>更新后的审批策略从下一轮 turn 开始生效。</p>
 */
@Component
public class ApprovalPolicySetHandler implements JsonRpcMethodHandler {

    /** 审批策略设置服务。 */
    private final ApprovalPolicyService approvalPolicyService;

    public ApprovalPolicySetHandler(ApprovalPolicyService approvalPolicyService) {
        this.approvalPolicyService = approvalPolicyService;
    }

    @Override
    public String method() {
        return "approval/policy/set";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        try {
            return SettingsGetHandler.payload(approvalPolicyService.setPolicy(requiredText(params, "approvalPolicy")));
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
