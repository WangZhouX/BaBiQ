package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.mcp.McpClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `mcp/tools/list` JSON-RPC handler。
 *
 * <p>按 serverId 返回 MCP 工具列表。工具 schema 和描述可以展示给用户，
 * 但工具输出仍只会在真实调用后走 spotlighting 和运行记录链路。</p>
 */
@Component
public class McpToolsListHandler implements JsonRpcMethodHandler {

    /** MCP client manager。 */
    private final McpClientManager manager;

    public McpToolsListHandler(McpClientManager manager) {
        this.manager = manager;
    }

    @Override
    public String method() {
        return "mcp/tools/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String serverId = McpRequestParams.requiredServerId(params);
        return Map.of("serverId", serverId, "tools", manager.tools(serverId));
    }
}
