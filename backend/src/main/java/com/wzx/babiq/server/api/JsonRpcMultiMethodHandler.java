package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;

/**
 * 可在同一个组件内处理多个相关 JSON-RPC method 的扩展接口。
 *
 * <p>它继承单 method 接口以保持 Spring 注入列表兼容，但实际注册和调用由
 * {@link JsonRpcDispatcher} 使用 {@link #methods()} 与三参数 handle 完成。</p>
 */
public interface JsonRpcMultiMethodHandler extends JsonRpcMethodHandler {

    /**
     * 返回该处理器声明的全部 method；集合必须非空，且每个名称非空白、无重复。
     */
    Set<String> methods();

    /**
     * 处理匹配到的具体 method，使同一组件可以共享协议上下文但仍区分调用入口。
     *
     * @param method 本次请求实际匹配的 method
     * @param params 请求参数节点
     * @param session 当前 WebSocket 会话
     * @return 会写入 Response.result 的业务对象
     * @throws Exception dispatcher 继续沿用现有统一错误映射
     */
    Object handle(String method, JsonNode params, WebSocketSession session) throws Exception;

    /**
     * 多 method handler 不存在唯一 method；dispatcher 会在注册前识别本接口。
     */
    @Override
    default String method() {
        throw new UnsupportedOperationException("多 method handler 没有唯一 method");
    }

    /**
     * 防止绕过 dispatcher 丢失实际 method；正常调用必须使用三参数版本。
     */
    @Override
    default Object handle(JsonNode params, WebSocketSession session) throws Exception {
        throw new UnsupportedOperationException("多 method handler 必须携带匹配 method 调用");
    }
}
