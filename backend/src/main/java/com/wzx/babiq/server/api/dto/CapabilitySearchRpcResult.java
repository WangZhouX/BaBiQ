package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * capability/search 响应。
 *
 * @param strategy 实际使用的搜索策略
 * @param results 搜索命中的能力摘要
 */
public record CapabilitySearchRpcResult(
        String strategy,
        List<CapabilityInfo> results
) {
}
