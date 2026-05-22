package com.wzx.babiq.server.api.error;

/**
 * JSON-RPC handler 主动抛出的协议异常。
 *
 * <p>业务 handler 不直接构造 ErrorResponse,而是抛出本异常交给 dispatcher
 * 统一映射。这样错误响应格式只在一个地方维护,也避免每个 handler 复制
 * JSON-RPC 错误封装逻辑。</p>
 */
public class JsonRpcException extends RuntimeException {

    private final JsonRpcErrorCode errorCode;
    private final Object errorData;

    /**
     * 创建不带 data 的协议异常。
     *
     * @param errorCode JSON-RPC 错误码
     * @param message 给客户端展示或记录的错误信息
     */
    public JsonRpcException(JsonRpcErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 创建带 data 的协议异常。
     *
     * @param errorCode JSON-RPC 错误码
     * @param message 给客户端展示或记录的错误信息
     * @param errorData 可选调试数据,会进入 error.data
     */
    public JsonRpcException(JsonRpcErrorCode errorCode, String message, Object errorData) {
        super(message);
        this.errorCode = errorCode;
        this.errorData = errorData;
    }

    /**
     * 返回用于 ErrorResponse 的协议错误码。
     *
     * @return JSON-RPC 错误码枚举
     */
    public JsonRpcErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回可选的 error.data。
     *
     * @return 额外错误上下文;没有上下文时为 null
     */
    public Object errorData() {
        return errorData;
    }
}
