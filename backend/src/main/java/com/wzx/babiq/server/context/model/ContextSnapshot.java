package com.wzx.babiq.server.context.model;

import java.time.Instant;
import java.util.List;

/**
 * 本轮模型可见上下文快照。
 *
 * <p>ContextSnapshot 是 P3 可观测性的核心对象：它记录模型实际看到了什么、什么被排除、
 * 以及每部分的估算 token。P3-1 先以内存对象返回，P3-2 再接持久化表。</p>
 *
 * @param threadId 当前业务会话 id
 * @param turnId 当前 turn id
 * @param createdAt 快照创建时间
 * @param estimatedTokens 本轮上下文估算总 token
 * @param items 快照条目，保持装配顺序
 */
public record ContextSnapshot(
        String threadId,
        String turnId,
        Instant createdAt,
        int estimatedTokens,
        List<ContextSnapshotItem> items
) {

    /**
     * 防御性复制条目，避免外部调用方在返回后修改快照。
     */
    public ContextSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
