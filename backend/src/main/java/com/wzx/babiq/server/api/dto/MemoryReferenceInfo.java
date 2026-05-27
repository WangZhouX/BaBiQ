package com.wzx.babiq.server.api.dto;

/**
 * 长期记忆引用摘要。
 *
 * @param artifactId 被注入或检索命中的 artifact id
 * @param confidence 置信度等级，当前为 medium，后续可接 VectorStore 分数
 * @param text 可注入模型的记忆片段文本
 * @param tokenEstimate 该片段的预估 token 数
 */
public record MemoryReferenceInfo(
        String artifactId,
        String confidence,
        String text,
        int tokenEstimate
) {
}
