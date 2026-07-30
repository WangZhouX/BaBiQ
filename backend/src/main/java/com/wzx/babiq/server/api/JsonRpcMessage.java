package com.wzx.babiq.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;

/**
 * JSON-RPC 2.0 报文族。
 *
 * <p>这是桌面端和后端 WebSocket 通信的最外层协议壳。P1-1 不引入第三方
 * JSON-RPC 框架,而是用 sealed interface + record 明确声明 request /
 * response / notification / error response 四种 wire 形态,方便后续对协议
 * 做可控演进。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface JsonRpcMessage
        permits JsonRpcMessage.Request,
        JsonRpcMessage.Response,
        JsonRpcMessage.Notification,
        JsonRpcMessage.ErrorResponse {

    /**
     * 返回 JSON-RPC 协议版本。
     *
     * @return 固定为 "2.0"
     */
    String jsonrpc();

    /**
     * 客户端发起的请求报文。
     *
     * <p>request 必须携带 id,服务端会用相同 id 返回 Response 或 ErrorResponse。</p>
     *
     * @param jsonrpc 协议版本,固定为 "2.0"
     * @param id 请求标识,用于关联响应
     * @param method JSON-RPC 方法名
     * @param params 方法参数,由具体 handler 解释
     */
    record Request(
            @JsonProperty(required = true) String jsonrpc,
            @JsonProperty(required = true) Long id,
            @JsonProperty(required = true) String method,
            Object params
    ) implements JsonRpcMessage {
        @Override
        public String toString() {
            return "Request[jsonrpc=" + jsonrpc + ", id=" + id + ", method=" + method
                    + ", params=[REDACTED]]";
        }
    }

    /**
     * 成功响应报文。
     *
     * <p>只用于 request/response 场景;服务端主动事件统一走 Notification。</p>
     *
     * @param jsonrpc 协议版本,固定为 "2.0"
     * @param id 对应请求 id
     * @param result handler 返回的业务结果
     */
    record Response(
            @JsonProperty(required = true) String jsonrpc,
            @JsonProperty(required = true) Long id,
            @JsonProperty(required = true) Object result
    ) implements JsonRpcMessage {

        /**
         * 创建标准成功响应。
         *
         * @param id 对应请求 id
         * @param result handler 返回的业务结果
         * @return JSON-RPC 2.0 成功响应
         */
        public static Response ok(Long id, Object result) {
            return new Response("2.0", id, result);
        }
    }

    /**
     * 服务端主动推送的通知报文。
     *
     * <p>notification 按 JSON-RPC 2.0 规范不携带 id,因此客户端不需要也不应该
     * 回应。turn/started、item/added 等流式事件都使用该类型。</p>
     *
     * @param jsonrpc 协议版本,固定为 "2.0"
     * @param method 通知方法名
     * @param params 通知参数
     */
    record Notification(
            @JsonProperty(required = true) String jsonrpc,
            @JsonProperty(required = true) String method,
            Object params
    ) implements JsonRpcMessage {

        /**
         * 创建标准服务端通知。
         *
         * @param method 通知方法名
         * @param params 通知参数
         * @return 不携带 id 的 JSON-RPC notification
         */
        public static Notification of(String method, Object params) {
            return new Notification("2.0", method, params);
        }
    }

    /**
     * 错误响应报文。
     *
     * <p>parse error 可能无法解析 request id,所以 id 允许为 null。其他错误应尽量
     * 带回原请求 id,让桌面端可以关联失败请求。</p>
     *
     * @param jsonrpc 协议版本,固定为 "2.0"
     * @param id 对应请求 id;解析阶段失败时允许为 null
     * @param error 标准 JSON-RPC error 对象
     */
    record ErrorResponse(
            @JsonProperty(required = true) String jsonrpc,
            Long id,
            @JsonProperty(required = true) Error error
    ) implements JsonRpcMessage {

        /**
         * JSON-RPC 标准 error 对象。
         *
         * @param code 整数错误码
         * @param message 错误描述
         * @param data 可选调试数据
         */
        public record Error(
                @JsonProperty(required = true) int code,
                @JsonProperty(required = true) String message,
                Object data
        ) {
        }

        /**
         * 创建标准错误响应。
         *
         * @param id 对应请求 id;解析失败时允许为 null
         * @param errorCode JSON-RPC 错误码
         * @param message 错误描述
         * @param data 可选调试数据
         * @return JSON-RPC 2.0 错误响应
         */
        public static ErrorResponse of(Long id, JsonRpcErrorCode errorCode, String message, Object data) {
            return new ErrorResponse("2.0", id, new Error(errorCode.code(), message, data));
        }
    }
}
