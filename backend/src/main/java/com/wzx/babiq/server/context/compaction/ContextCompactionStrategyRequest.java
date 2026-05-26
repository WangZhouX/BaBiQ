package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.context.model.ShortTermSummary;

/**
 * 传给压缩策略的结构化请求。
 *
 * @param threadId 所属会话 id
 * @param turnId 触发压缩的 turn id
 * @param providerId 摘要模型 Provider id
 * @param model 摘要模型名
 * @param source 本次压缩来源历史
 * @param activeSummary 当前已安装的摘要，可为空
 * @param currentUserMessage 当前用户输入，只作为压缩边界提示，不允许被摘要吞掉
 */
public record ContextCompactionStrategyRequest(
        String threadId,
        String turnId,
        String providerId,
        String model,
        CompactionSource source,
        ShortTermSummary activeSummary,
        String currentUserMessage
) {
}
