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
    SERVER_ERROR(-32000),

    BUSINESS_AUTH_REQUIRED(-32010),
    BUSINESS_ACCOUNT_NOT_FOUND(-32011),
    BUSINESS_TENANT_UNAVAILABLE(-32012),
    BUSINESS_INVALID_CREDENTIALS(-32013),
    BUSINESS_AUTH_EXPIRED(-32014),
    BUSINESS_MEMBERSHIP_EXPIRED(-32015),
    BUSINESS_SESSION_STALE(-32016),
    BUSINESS_LOCAL_SECRET_STORE_FAILED(-32017),
    BUSINESS_CONFIG_INVALID(-32018),
    BUSINESS_SESSION_NOT_ATTACHABLE(-32019),
    BUSINESS_PERMISSION_DENIED(-32020),
    BUSINESS_SCOPE_INVALID(-32021),
    BUSINESS_SCOPE_CHANGED(-32022),
    BUSINESS_VALIDATION_FAILED(-32030),
    BUSINESS_CONFLICT(-32031),
    BUSINESS_OUTCOME_UNKNOWN(-32032),

    BUSINESS_REMOTE_UNAVAILABLE(-32040),
    BUSINESS_REMOTE_TIMEOUT(-32042),
    BUSINESS_REMOTE_PROTOCOL_ERROR(-32043),

    /** UTF-8 JSON-RPC envelope 超过字节上限时使用的稳定协议错误码。 */
    PROTOCOL_ERROR(-32041),

    BUSINESS_UPLOAD_REJECTED(-32050),
    BUSINESS_RESOURCE_UNAVAILABLE(-32051);

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
