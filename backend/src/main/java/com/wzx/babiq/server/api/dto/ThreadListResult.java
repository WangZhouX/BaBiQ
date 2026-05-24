package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * thread/list 的响应 DTO。
 *
 * @param threads 当前页最近会话列表，按 updatedAt 倒序排列
 * @param nextCursor 下一页游标；P2-2 先返回 null，预留给后续大列表分页
 */
public record ThreadListResult(
        List<ThreadSummaryDto> threads,
        String nextCursor
) {
    public ThreadListResult {
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
