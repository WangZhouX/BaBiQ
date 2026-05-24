package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;

/**
 * MCP JSON-RPC 参数读取工具。
 */
final class McpRequestParams {

    private McpRequestParams() {
    }

    /**
     * 读取必填 serverId。
     */
    static String requiredServerId(JsonNode params) {
        JsonNode value = params == null ? null : params.get("serverId");
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: serverId");
        }
        return value.asText();
    }
}
