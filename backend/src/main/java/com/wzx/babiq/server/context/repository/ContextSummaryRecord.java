package com.wzx.babiq.server.context.repository;

import java.time.Instant;

/**
 * 短期上下文摘要领域记录。
 *
 * <p>摘要记录是 BaBiQ 短期记忆的事实源之一：模型输入可以引用它，但原始 ThreadItem
 * 仍保留在 bq_items 中，便于审计和后续重新压缩。</p>
 *
 * @param summaryId 协议层摘要 id，以 ctxsum_ 开头
 * @param threadId 所属会话 id
 * @param sourceItemRange 摘要覆盖的 item 范围
 * @param sourceStartItemId 摘要覆盖起点 item id
 * @param sourceEndItemId 摘要覆盖终点 item id
 * @param summary 摘要正文
 * @param providerId 生成摘要时使用的 Provider id
 * @param model 生成摘要时使用的模型名
 * @param estimatedTokens 摘要正文预估 token 数
 * @param createdAt 创建时间
 */
public record ContextSummaryRecord(
        String summaryId,
        String threadId,
        String sourceItemRange,
        String sourceStartItemId,
        String sourceEndItemId,
        String summary,
        String providerId,
        String model,
        int estimatedTokens,
        Instant createdAt
) {
}
