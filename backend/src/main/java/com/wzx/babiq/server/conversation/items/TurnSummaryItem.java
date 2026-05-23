package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 单轮 Agent 执行摘要 item。
 *
 * <p>该 item 在 turn 结束前发出,让桌面端可以展示本轮模型、token、工具调用、
 * 估算成本和耗时,同时作为 P1 可观测性的协议边界。</p>
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
        BigDecimal estimatedCostUsd,
        long durationMs
) implements ThreadItem {
}
