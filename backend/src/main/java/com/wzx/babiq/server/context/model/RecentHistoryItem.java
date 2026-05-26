package com.wzx.babiq.server.context.model;

/**
 * 模型可见的近期历史条目。
 *
 * <p>它不是数据库 ThreadItem 的原样副本，而是 ContextAssembler 过滤后的模型可见片段。
 * 这样 UI transcript、运行摘要和工具观测不会误入 prompt。</p>
 *
 * @param itemId 来源 ThreadItem id，用于追溯和 snapshot 对齐
 * @param role 模型角色，当前只暴露 user 和 assistant
 * @param text 进入模型的正文
 * @param tokenEstimate 该正文的 token 估算值
 */
public record RecentHistoryItem(
        String itemId,
        String role,
        String text,
        int tokenEstimate
) {
}
