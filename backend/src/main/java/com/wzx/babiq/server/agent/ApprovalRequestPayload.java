package com.wzx.babiq.server.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * approval/request 通知载荷。
 *
 * <p>该 record 是 D23 HITL 中断后发给桌面端的最小协议对象。它存在的原因是审批请求
 * 不是持久化 ThreadItem，而是一次 JSON-RPC notification；AgentLoop 会从 SAA
 * InterruptionMetadata 展开出该载荷，ItemEmitter 负责发送。</p>
 *
 * @param threadId 业务线程 id
 * @param turnId 当前 turn id
 * @param itemId 审批项 id，approval/respond 需要原样带回
 * @param toolName 待审批工具名
 * @param arguments 工具参数 JSON 字符串
 * @param description 展示给用户看的审批说明
 */
public record ApprovalRequestPayload(
        @JsonProperty(required = true) String threadId,
        @JsonProperty(required = true) String turnId,
        @JsonProperty(required = true) String itemId,
        @JsonProperty(required = true) String toolName,
        @JsonProperty(required = true) String arguments,
        String description
) {
}
