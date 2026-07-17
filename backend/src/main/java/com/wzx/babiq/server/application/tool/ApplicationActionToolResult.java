package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 返回给模型的短动作终态，不携带桌面原始结果或敏感值。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationActionToolResult(
        String executionId,
        String status,
        String errorCode,
        String summary
) {
}
