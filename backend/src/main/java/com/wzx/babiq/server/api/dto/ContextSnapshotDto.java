package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * 上下文快照详情 DTO。
 *
 * @param snapshotId 快照 id。
 * @param threadId 所属会话 id。
 * @param turnId 所属 turn id。
 * @param phase 快照阶段。
 * @param providerId 本轮 Provider id。
 * @param model 本轮模型名。
 * @param cwd 本轮工作目录。
 * @param windowOrdinal 所属窗口序号。
 * @param modelContextWindow 模型上下文窗口 token 数。
 * @param autoCompactThreshold 自动压缩阈值 token 数。
 * @param estimatedTokens 预估上下文 token 数。
 * @param actualPromptTokens 真实 prompt token，供应商未返回时为空。
 * @param includedItemCount 纳入模型输入的片段数。
 * @param excludedItemCount 排除但记录审计的片段数。
 * @param usageRatio 当前 token 使用率。
 * @param inputPreview 本轮输入预览。
 * @param createdAt 快照创建时间。
 * @param items 快照条目列表。
 */
public record ContextSnapshotDto(
        String snapshotId,
        String threadId,
        String turnId,
        String phase,
        String providerId,
        String model,
        String cwd,
        int windowOrdinal,
        int modelContextWindow,
        int autoCompactThreshold,
        int estimatedTokens,
        Long actualPromptTokens,
        int includedItemCount,
        int excludedItemCount,
        double usageRatio,
        String inputPreview,
        String createdAt,
        List<ContextSnapshotItemDto> items
) {
    public ContextSnapshotDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
