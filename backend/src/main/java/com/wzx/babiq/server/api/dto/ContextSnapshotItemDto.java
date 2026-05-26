package com.wzx.babiq.server.api.dto;

/**
 * 上下文快照条目 DTO。
 *
 * @param sourceId 上下文来源 id，例如 item id、memory artifact id 或 capability name。
 * @param sourceType 来源类型，使用字符串避免桌面端绑定 Java enum。
 * @param priority 分层优先级，说明该片段对模型输入的重要程度。
 * @param included 是否实际进入模型输入。
 * @param reason 纳入或排除原因。
 * @param tokenEstimate 该片段的 token 预估值。
 */
public record ContextSnapshotItemDto(
        String sourceId,
        String sourceType,
        String priority,
        boolean included,
        String reason,
        int tokenEstimate
) {
}
