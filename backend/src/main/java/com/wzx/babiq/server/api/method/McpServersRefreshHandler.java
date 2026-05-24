package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.mcp.McpClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `mcp/servers/refresh` JSON-RPC handler。
 *
 * <p>手动刷新一个 MCP server 的连接和工具列表。刷新可能失败，但失败会以 server 状态返回，
 * 不应该让桌面端或聊天主流程崩溃。</p>
 */
@Component
public class McpServersRefreshHandler implements JsonRpcMethodHandler {

    /** MCP client manager。 */
    private final McpClientManager manager;

    public McpServersRefreshHandler(McpClientManager manager) {
        this.manager = manager;
    }

    @Override
    public String method() {
        return "mcp/servers/refresh";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String serverId = McpRequestParams.requiredServerId(params);
        return Map.of("server", manager.refresh(serverId));
    }
}
