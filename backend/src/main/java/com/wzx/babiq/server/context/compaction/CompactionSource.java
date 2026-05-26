package com.wzx.babiq.server.context.compaction;

import java.util.List;

/**
 * 一次压缩调用的来源历史集合。
 *
 * @param items 本次将交给压缩模型的历史消息
 * @param sourceItemRange 便于人读和持久化审计的来源范围，例如 it_1..it_8
 * @param sourceStartItemId 来源起点 item id
 * @param sourceEndItemId 来源终点 item id
 */
public record CompactionSource(
        List<CompactionSourceItem> items,
        String sourceItemRange,
        String sourceStartItemId,
        String sourceEndItemId
) {

    /**
     * 归一化列表字段，避免后续策略重复做 null 防御。
     */
    public CompactionSource {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @return true 表示没有可压缩的模型可见历史
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
