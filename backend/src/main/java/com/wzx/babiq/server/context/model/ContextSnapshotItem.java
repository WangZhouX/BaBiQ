package com.wzx.babiq.server.context.model;

/**
 * 上下文快照中的单个来源片段。
 *
 * <p>无论片段最终是否进入模型，都记录到 snapshot 中。这样运行详情面板后续可以解释
 * included/excluded/tokenEstimate/sourceId，而不是只展示最终 prompt。</p>
 *
 * @param sourceId 来源 id，例如 item id、memory artifact id 或 capability name
 * @param sourceType 来源类型
 * @param priority 分层优先级
 * @param included 是否进入本轮模型可见上下文
 * @param reason 进入或排除原因的人类可读说明
 * @param tokenEstimate token 估算值，排除项也记录便于预算分析
 */
public record ContextSnapshotItem(
        String sourceId,
        ContextSourceType sourceType,
        ContextPriority priority,
        boolean included,
        String reason,
        int tokenEstimate
) {
}
