package com.wzx.babiq.server.approval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 发给前端的审批请求载体。
 *
 * @param threadId 线程 id
 * @param turnId turn id
 * @param approvalId 审批 id
 * @param tool 工具名
 * @param args 工具参数
 * @param reason 申请原因
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalRequest(
        @JsonProperty(required = true) String threadId,
        @JsonProperty(required = true) String turnId,
        @JsonProperty(required = true) String approvalId,
        @JsonProperty(required = true) String tool,
        @JsonProperty(required = true) Map<String, Object> args,
        String reason
) {
}
