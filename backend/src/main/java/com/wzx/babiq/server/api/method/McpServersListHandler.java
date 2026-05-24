package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.mcp.McpClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * `mcp/servers/list` JSON-RPC handler。
 *
 * <p>返回本地 MCP server 状态列表。它只读取 McpClientManager 的状态视图，
 * 不直接启动进程，也不暴露 command/args 这类执行细节给桌面端。</p>
 */
@Component
public class McpServersListHandler implements JsonRpcMethodHandler {

    /** MCP client manager。 */
    private final McpClientManager manager;

    public McpServersListHandler(McpClientManager manager) {
        this.manager = manager;
    }

    @Override
    public String method() {
        return "mcp/servers/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        return Map.of("servers", manager.servers());
    }
}
