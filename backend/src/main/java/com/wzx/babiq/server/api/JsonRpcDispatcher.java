package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.api.BusinessJsonRpcAccessPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON-RPC 方法路由器。
 *
 * <p>WebSocket handler 只负责解析报文和写回响应,真正的 method 分发集中在这里。
 * 这样错误码映射、未知方法处理和 handler 异常保护都只有一份实现。</p>
 */
@Component
public class JsonRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcDispatcher.class);

    /** method 名称到 handler 的索引表，例如 turn/start -> TurnStartHandler。 */
    private final Map<String, JsonRpcMethodHandler> handlers;
    /** Jackson JSON 编解码器，负责把 params 转成 handler 需要的 Java 类型。 */
    private final ObjectMapper objectMapper;
    /** 仅 business profile 注入的 default-deny 策略；普通 profile 为 null。 */
    private final BusinessJsonRpcAccessPolicy businessAccessPolicy;

    /**
     * 创建 dispatcher 并注册所有 method handler。
     *
     * @param allHandlers Spring 注入的所有 handler
     * @param objectMapper JSON 参数转换器
     * @throws IllegalStateException 出现重复 method 时抛出
     */
    public JsonRpcDispatcher(List<JsonRpcMethodHandler> allHandlers, ObjectMapper objectMapper) {
        this(allHandlers, objectMapper, null);
    }

    /** Spring 构造器通过 provider 保持普通 Agent profile 无业务策略依赖。 */
    @Autowired
    public JsonRpcDispatcher(
            List<JsonRpcMethodHandler> allHandlers,
            ObjectMapper objectMapper,
            ObjectProvider<BusinessJsonRpcAccessPolicy> businessAccessPolicyProvider) {
        this.objectMapper = objectMapper;
        this.businessAccessPolicy = businessAccessPolicyProvider == null
                ? null
                : businessAccessPolicyProvider.getIfAvailable();
        this.handlers = indexHandlers(allHandlers);
        log.info("JSON-RPC method 注册完成: count={}, methods={}", handlers.size(), handlers.keySet());
    }

    /**
     * 分发 JSON-RPC request 并返回 response/error response。
     *
     * @param request 已解析的 JSON-RPC request
     * @param session 当前 WebSocket 会话
     * @return 成功响应或错误响应
     */
    public JsonRpcMessage dispatch(JsonRpcMessage.Request request, WebSocketSession session) {
        JsonRpcMethodHandler handler = handlers.get(request.method());
        boolean accessDenied = businessAccessPolicy != null
                && !businessAccessPolicy.isAllowed(
                        request.method(), session == null ? null : session.getId());
        if (handler == null || accessDenied) {
            log.warn("JSON-RPC 未知 method: requestId={}, method={}, availableMethods={}",
                    request.id(), request.method(), handlers.keySet());
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    JsonRpcErrorCode.METHOD_NOT_FOUND,
                    "Method not found: " + request.method(),
                    null);
        }

        return callHandler(request, session, handler);
    }

    /**
     * 路由客户端 notification；只允许显式声明该 method 的 multi-method handler，且不构造响应。
     *
     * @return 已找到并成功调用 handler 时为 true；未知或被策略拒绝时为 false
     */
    public boolean dispatchNotification(JsonRpcMessage.Notification notification, WebSocketSession session) {
        JsonRpcMethodHandler handler = handlers.get(notification.method());
        boolean accessDenied = businessAccessPolicy != null
                && !businessAccessPolicy.isAllowed(
                        notification.method(), session == null ? null : session.getId());
        if (!(handler instanceof JsonRpcMultiMethodHandler multiMethodHandler) || accessDenied) {
            return false;
        }
        try {
            JsonNode params = notification.params() == null
                    ? objectMapper.nullNode()
                    : objectMapper.valueToTree(notification.params());
            multiMethodHandler.handle(notification.method(), params, session);
            return true;
        } catch (Exception exception) {
            log.warn("JSON-RPC notification 执行失败: method={}, handler={}, errorType={}",
                    notification.method(), handler.getClass().getSimpleName(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private JsonRpcMessage callHandler(
            JsonRpcMessage.Request request,
            WebSocketSession session,
            JsonRpcMethodHandler handler) {
        long startedNanos = System.nanoTime();
        try {
            // request.params 在 record 里是 Object，这里统一转为 JsonNode，方便各 handler 做字段校验。
            JsonNode params = request.params() == null
                    ? objectMapper.nullNode()
                    : objectMapper.valueToTree(request.params());
            log.debug("JSON-RPC method 开始执行: requestId={}, method={}, handler={}, sessionId={}, params={}",
                    request.id(),
                    request.method(),
                    handler.getClass().getSimpleName(),
                    session == null ? "null" : session.getId(),
                    JsonRpcLogSupport.paramsSummary(request.method(), params));
            Object responsePayload = handler instanceof JsonRpcMultiMethodHandler multiMethodHandler
                    ? multiMethodHandler.handle(request.method(), params, session)
                    : handler.handle(params, session);
            log.info("JSON-RPC method 执行成功: requestId={}, method={}, handler={}, elapsedMs={}, resultType={}",
                    request.id(),
                    request.method(),
                    handler.getClass().getSimpleName(),
                    JsonRpcLogSupport.elapsedMillis(startedNanos),
                    responsePayload == null ? "null" : responsePayload.getClass().getSimpleName());
            return JsonRpcMessage.Response.ok(request.id(), responsePayload);
        } catch (JsonRpcException jsonRpcException) {
            // JsonRpcException 是预期内的协议/业务错误，按它携带的标准 JSON-RPC 错误码返回。
            log.warn("JSON-RPC method 参数/业务错误: requestId={}, method={}, code={}, message={}, elapsedMs={}",
                    request.id(),
                    request.method(),
                    jsonRpcException.errorCode().code(),
                    JsonRpcLogSupport.preview(jsonRpcException.getMessage()),
                    JsonRpcLogSupport.elapsedMillis(startedNanos));
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    jsonRpcException.errorCode(),
                    jsonRpcException.getMessage(),
                    jsonRpcException.errorData());
        } catch (Exception exception) {
            // 未预期异常统一映射为 SERVER_ERROR，避免把 Java 栈细节泄露给桌面端协议。
            log.error("JSON-RPC method 执行失败: requestId={}, method={}, handler={}, elapsedMs={}, errorType={}",
                    request.id(),
                    request.method(),
                    handler.getClass().getSimpleName(),
                    JsonRpcLogSupport.elapsedMillis(startedNanos),
                    exception.getClass().getSimpleName());
            return JsonRpcMessage.ErrorResponse.of(
                    request.id(),
                    JsonRpcErrorCode.SERVER_ERROR,
                    "Internal server error",
                    null);
        }
    }

    /**
     * 将 Spring 注入的 handler 列表整理为 method -> handler 映射。
     */
    private Map<String, JsonRpcMethodHandler> indexHandlers(List<JsonRpcMethodHandler> allHandlers) {
        Map<String, JsonRpcMethodHandler> indexedHandlers = new LinkedHashMap<>();
        for (JsonRpcMethodHandler handler : allHandlers) {
            if (handler instanceof JsonRpcMultiMethodHandler multiMethodHandler) {
                indexMultiMethodHandler(indexedHandlers, multiMethodHandler);
            } else {
                indexMethod(indexedHandlers, handler.method(), handler);
            }
        }
        return Map.copyOf(indexedHandlers);
    }

    /**
     * 校验并注册一个多 method handler，避免空声明、空白名称或异常 Set 实现隐藏重复值。
     */
    private void indexMultiMethodHandler(
            Map<String, JsonRpcMethodHandler> indexedHandlers,
            JsonRpcMultiMethodHandler handler) {
        Set<String> methods = handler.methods();
        if (methods == null || methods.isEmpty()) {
            throw new IllegalStateException("JSON-RPC 多 method handler 至少声明一个 method");
        }

        Set<String> declaredMethods = new LinkedHashSet<>();
        for (String method : methods) {
            if (method == null || method.isBlank()) {
                throw new IllegalStateException("JSON-RPC method 不能为空");
            }
            if (!declaredMethods.add(method)) {
                throw new IllegalStateException("JSON-RPC method 重复注册: " + method);
            }
            indexMethod(indexedHandlers, method, handler);
        }
    }

    /**
     * 注册单个 method，并统一检测 single/multi handler 之间的名称冲突。
     */
    private void indexMethod(
            Map<String, JsonRpcMethodHandler> indexedHandlers,
            String method,
            JsonRpcMethodHandler handler) {
        // putIfAbsent 能在注册阶段直接发现重复 method，避免运行时路由到随机 handler。
        JsonRpcMethodHandler previousHandler = indexedHandlers.putIfAbsent(method, handler);
        if (previousHandler != null) {
            throw new IllegalStateException("JSON-RPC method 重复注册: " + method);
        }
    }
}
