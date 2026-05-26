package com.wzx.babiq.server.context.compaction;

/**
 * 可进入短期压缩提示词的一条历史消息。
 *
 * @param itemId 原始 ThreadItem id，用于回写摘要覆盖范围和审计追踪
 * @param role 归一化后的对话角色，只允许 user 或 assistant
 * @param text 模型可见文本，已经过滤运行反馈和空内容
 */
public record CompactionSourceItem(
        String itemId,
        String role,
        String text
) {
}
