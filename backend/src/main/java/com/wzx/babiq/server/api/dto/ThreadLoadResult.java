package com.wzx.babiq.server.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * thread/load 的响应 DTO。
 *
 * <p>items 使用 JsonNode 而不是重新建一套 Java DTO，是为了把 bq_items.payload_json 原样交还给桌面端。
 * 这样实时 WebSocket item 和历史恢复 item 共用同一份协议模型。</p>
 *
 * @param thread 会话元信息
 * @param items 按 sequence_no 正序返回的原始 item JSON
 * @param latestSummary 当前页中最新的 turnSummary item；没有摘要时为空
 * @param nextBeforeItemId 继续加载更早 item 时使用的游标；没有更早数据时为空
 */
public record ThreadLoadResult(
        ThreadMetaDto thread,
        List<JsonNode> items,
        JsonNode latestSummary,
        String nextBeforeItemId
) {
    public ThreadLoadResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
