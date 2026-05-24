package com.wzx.babiq.server.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * run/turn/get 响应。
 *
 * @param turn turn 快照摘要
 * @param items 该 turn 对应的协议 item 原始 JSON
 * @param summary 合成的 turnSummary JSON；没有摘要时为空
 * @param approvals 该 turn 的审批记录
 * @param toolCalls 该 turn 的工具调用记录
 */
public record RunTurnDetailResult(
        RunTurnSummaryDto turn,
        List<JsonNode> items,
        JsonNode summary,
        List<RunApprovalDto> approvals,
        List<RunToolCallDto> toolCalls
) {
    public RunTurnDetailResult {
        items = items == null ? List.of() : List.copyOf(items);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
