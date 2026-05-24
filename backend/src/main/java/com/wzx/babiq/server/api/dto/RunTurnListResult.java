package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * run/turns/list 响应。
 *
 * @param turns 按 startedAt 倒序返回的 turn 摘要
 * @param nextCursor 下一页游标；P2-4 先返回空，字段保留给后续分页增强
 */
public record RunTurnListResult(
        List<RunTurnSummaryDto> turns,
        String nextCursor
) {
    public RunTurnListResult {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
