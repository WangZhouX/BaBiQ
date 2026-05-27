package com.wzx.babiq.server.capability;

/**
 * 能力搜索请求。
 *
 * @param threadId 来源 thread，可为空
 * @param turnId 来源 turn，可为空
 * @param queryText 搜索词
 * @param limit 最大返回数量
 * @param recordEvent 是否写入搜索审计
 */
public record CapabilitySearchRequest(
        String threadId,
        String turnId,
        String queryText,
        int limit,
        boolean recordEvent
) {
}
