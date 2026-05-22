package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.socket.WebSocketSession;

/**
 * JSON-RPC 方法处理器接口。
 *
 * <p>每个 method handler 只处理一个 JSON-RPC method,由 dispatcher 根据
 * {@link #method()} 统一路由。params 使用 JsonNode 是为了让 handler 自己声明
 * 参数契约,同时避免在 dispatcher 层硬编码所有方法的 DTO。</p>
 */
public interface JsonRpcMethodHandler {

    /**
     * 返回该 handler 负责的 JSON-RPC method。
     *
     * @return 例如 thread/create 或 turn/start
     */
    String method();

    /**
     * 处理一次 JSON-RPC 请求。
     *
     * @param params 请求参数节点;请求未带 params 时为 NullNode
     * @param session 当前 WebSocket 会话
     * @return 会写入 Response.result 的业务对象
     * @throws Exception handler 可以抛出 JsonRpcException 或其他异常,dispatcher 会统一映射
     */
    Object handle(JsonNode params, WebSocketSession session) throws Exception;
}
