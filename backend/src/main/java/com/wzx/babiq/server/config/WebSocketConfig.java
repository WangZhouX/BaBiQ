package com.wzx.babiq.server.config;

import com.wzx.babiq.server.api.JsonRpcWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点配置。
 *
 * <p>P1-1 使用 Spring 原生 WebSocket API 注册 /ws/agent,不引入 Netty 或额外
 * RPC 框架。路径和允许来源来自 application.yml,方便后续 P1-3b 收紧安全策略。</p>
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /** BaBiQ JSON-RPC WebSocket 处理器，所有桌面端协议帧都会进入它。 */
    private final JsonRpcWebSocketHandler jsonRpcWebSocketHandler;
    /** WebSocket 注册路径，默认 /ws/agent，桌面端 DesktopConfig 必须与它一致。 */
    private final String wsPath;
    /** 允许跨域连接的来源列表，本地桌面端开发通常使用 *。 */
    private final String allowedOrigins;

    /**
     * 创建 WebSocket 配置。
     *
     * @param jsonRpcWebSocketHandler JSON-RPC WebSocket handler
     * @param wsPath WebSocket 注册路径
     * @param allowedOrigins 允许的 Origin 列表表达式
     */
    public WebSocketConfig(
            JsonRpcWebSocketHandler jsonRpcWebSocketHandler,
            @Value("${babiq.ws.path:/ws/agent}") String wsPath,
            @Value("${babiq.ws.allowed-origins:*}") String allowedOrigins) {
        this.jsonRpcWebSocketHandler = jsonRpcWebSocketHandler;
        this.wsPath = wsPath;
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * 注册 WebSocket handler。
     *
     * @param registry Spring WebSocket handler 注册表
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(jsonRpcWebSocketHandler, wsPath).setAllowedOrigins(allowedOrigins);
    }
}
