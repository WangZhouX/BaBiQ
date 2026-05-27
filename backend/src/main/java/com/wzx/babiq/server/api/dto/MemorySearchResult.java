package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * memory/search 响应。
 *
 * @param strategy 检索策略名称
 * @param references 命中的长期记忆引用
 * @param tokenEstimate 本次返回引用的总预估 token 数
 */
public record MemorySearchResult(
        String strategy,
        List<MemoryReferenceInfo> references,
        int tokenEstimate
) {
}
