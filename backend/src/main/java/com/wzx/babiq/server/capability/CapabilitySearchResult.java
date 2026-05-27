package com.wzx.babiq.server.capability;

import java.util.List;

/**
 * 能力搜索结果。
 *
 * @param strategy 实际使用的搜索策略
 * @param results 返回给模型或 UI 的能力列表
 */
public record CapabilitySearchResult(
        String strategy,
        List<CapabilityDescriptor> results
) {

    /**
     * 防御性复制，避免调用方修改搜索结果影响审计语义。
     */
    public CapabilitySearchResult {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
