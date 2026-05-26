package com.wzx.babiq.server.context.repository;

import java.time.Instant;

/**
 * 单轮模型调用前的上下文快照记录。
 *
 * <p>该 record 只保存运行时审计需要的结构化 JSON 和统计字段，不替代原始 ThreadItem 历史。</p>
 *
 * @param snapshotId 协议层快照 id。
 * @param threadId 所属会话 id。
 * @param turnId 所属 turn id。
 * @param phase 快照阶段，P3-2 固定为 pre_model_call。
 * @param providerId 本轮 Provider id。
 * @param model 本轮模型名。
 * @param cwd 本轮工作目录。
 * @param windowOrdinal 所属窗口序号。
 * @param modelContextWindow 模型上下文窗口 token 数。
 * @param autoCompactThreshold 自动压缩阈值 token 数。
 * @param estimatedTokens 预估上下文 token 数。
 * @param actualPromptTokens 供应商返回的真实 prompt token，缺失时为空。
 * @param includedItemCount 纳入模型输入的片段数量。
 * @param excludedItemCount 排除但记录审计的片段数量。
 * @param envelopeJson 分层上下文 JSON。
 * @param itemsJson 快照条目 JSON。
 * @param capabilityCatalogJson 能力目录摘要 JSON。
 * @param longTermMemoryRefsJson 本轮注入的长期记忆引用 JSON。
 * @param longTermMemoryTokenEstimate 长期记忆引用 token 估算。
 * @param inputPreview 本轮输入预览。
 * @param createdAt 快照创建时间。
 */
public record ContextSnapshotRecord(
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
        String envelopeJson,
        String itemsJson,
        String capabilityCatalogJson,
        String longTermMemoryRefsJson,
        int longTermMemoryTokenEstimate,
        String inputPreview,
        Instant createdAt
) {
    /**
     * 兼容 P3-2/P3-3 旧调用点的构造器；未接入长期记忆时相关字段为空和 0。
     */
    public ContextSnapshotRecord(String snapshotId,
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
                                 String envelopeJson,
                                 String itemsJson,
                                 String capabilityCatalogJson,
                                 String inputPreview,
                                 Instant createdAt) {
        this(snapshotId, threadId, turnId, phase, providerId, model, cwd, windowOrdinal, modelContextWindow,
                autoCompactThreshold, estimatedTokens, actualPromptTokens, includedItemCount, excludedItemCount,
                envelopeJson, itemsJson, capabilityCatalogJson, null, 0, inputPreview, createdAt);
    }
}
