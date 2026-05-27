package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * capability/status 响应。
 *
 * @param totalCount 已登记能力总数
 * @param enabledCount 启用能力数量
 * @param visibleCount 默认可见能力数量
 * @param deferredCount 需要 tool_search 按需发现的能力数量
 * @param disabledCount 被禁用能力数量
 * @param capabilities 能力摘要列表
 */
public record CapabilityStatusResult(
        int totalCount,
        int enabledCount,
        int visibleCount,
        int deferredCount,
        int disabledCount,
        List<CapabilityInfo> capabilities
) {
}
