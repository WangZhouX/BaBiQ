package com.wzx.babiq.server.api.error;

/**
 * JSON-RPC 2.0 错误码枚举。
 *
 * <p>协议层所有错误都必须落到标准错误码上,这样桌面端可以只依赖
 * code 做稳定分支,不用解析不稳定的 message 文案。P1-1 只覆盖规范
 * 保留码和一个通用服务端错误码。</p>
 */
public enum JsonRpcErrorCode {

    /** JSON 文本解析失败,来自 JSON-RPC 2.0 规范保留错误码。 */
    PARSE_ERROR(-32700),

    /** 报文不是合法 JSON-RPC Request,来自 JSON-RPC 2.0 规范保留错误码。 */
    INVALID_REQUEST(-32600),

    /** 请求的方法名没有注册 handler,来自 JSON-RPC 2.0 规范保留错误码。 */
    METHOD_NOT_FOUND(-32601),

    /** 参数缺失或类型不符合 handler 契约,来自 JSON-RPC 2.0 规范保留错误码。 */
    INVALID_PARAMS(-32602),

    /** 协议栈内部错误,来自 JSON-RPC 2.0 规范保留错误码。 */
    INTERNAL_ERROR(-32603),

    /** 业务 handler 抛出非协议异常时使用的服务端错误码区间。 */
    SERVER_ERROR(-32000);

    private final int code;

    JsonRpcErrorCode(int code) {
        this.code = code;
    }

    /**
     * 返回 wire 协议中实际写入 JSON 的整数错误码。
     *
     * @return JSON-RPC 2.0 规定或 BaBiQ 协议约定的整数错误码
     */
    public int code() {
        return code;
    }
}
