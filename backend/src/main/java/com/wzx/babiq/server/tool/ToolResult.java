package com.wzx.babiq.server.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 工具统一返回载体。
 *
 * @param ok 是否成功
 * @param output 成功时输出
 * @param error 失败原因
 * @param truncated 是否被截断
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        @JsonProperty(required = true) boolean ok,
        @JsonProperty(required = true) String output,
        String error,
        @JsonProperty(required = true) boolean truncated
) {

    /**
     * 构造成功结果。
     *
     * @param output 成功输出
     * @return 成功 ToolResult
     */
    public static ToolResult ok(String output) {
        return new ToolResult(true, output == null ? "" : output, null, false);
    }

    /**
     * 构造失败结果。
     *
     * @param error 失败原因
     * @return 失败 ToolResult
     */
    public static ToolResult failure(String error) {
        return new ToolResult(false, "", error, false);
    }
}
