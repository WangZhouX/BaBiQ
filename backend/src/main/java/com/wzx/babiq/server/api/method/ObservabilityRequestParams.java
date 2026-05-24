package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;

import java.util.Locale;

/**
 * P2-5 本地可观测接口的参数解析工具。
 *
 * <p>三个 observability/* handler 都只有 range 和 cwd 两个入参。把解析逻辑集中在这里，
 * 可以让每个 handler 专注表达“调用哪个 service 方法”，也避免不同接口对默认 range 的理解不一致。</p>
 */
final class ObservabilityRequestParams {

    /** 默认统计窗口；桌面运行详情面板初次打开时使用最近 7 天。 */
    private static final String DEFAULT_RANGE = "7d";

    private ObservabilityRequestParams() {
    }

    /**
     * 读取统计窗口。
     *
     * @param params JSON-RPC params 节点，允许为空。
     * @return 小写后的 range；缺失或空白时返回 7d。
     */
    static String rangeOrDefault(JsonNode params) {
        if (params == null || !params.hasNonNull("range") || params.get("range").asText().isBlank()) {
            return DEFAULT_RANGE;
        }
        return params.get("range").asText().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 读取可选工作目录过滤。
     *
     * @param params JSON-RPC params 节点，允许为空。
     * @return 非空 cwd；缺失或空白时返回 null，表示统计所有工作目录。
     */
    static String optionalCwd(JsonNode params) {
        if (params == null || !params.hasNonNull("cwd") || params.get("cwd").asText().isBlank()) {
            return null;
        }
        return params.get("cwd").asText();
    }

    /**
     * 把 service 抛出的参数错误转换成 JSON-RPC 标准错误。
     *
     * @param exception service 层参数异常。
     * @return 可由 dispatcher 直接序列化的 JSON-RPC 异常。
     */
    static JsonRpcException invalidParams(IllegalArgumentException exception) {
        return new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
    }
}
