package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单轮 Agent 执行摘要 item。
 *
 * <p>该 item 在 turn 结束前发出，让桌面端可以展示本轮模型、token、工具调用和耗时。
 * BaBiQ 当前只记录真实 token 用量，不在协议 item 中传输价格或成本。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TurnSummaryItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String status,
        @JsonProperty(required = true) String model,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        int toolCalls,
        long durationMs
) implements ThreadItem {
}
