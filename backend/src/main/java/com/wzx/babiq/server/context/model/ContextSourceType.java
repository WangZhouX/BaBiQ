package com.wzx.babiq.server.context.model;

/**
 * 上下文快照条目的来源类型。
 *
 * <p>UI 和调试日志需要知道每个可见片段来自哪里，才能解释“为什么本轮模型看到了这些内容”。</p>
 */
public enum ContextSourceType {
    /** 本轮用户输入和运行策略。 */
    CURRENT_TURN,
    /** SQLite 历史中的 ThreadItem。 */
    THREAD_ITEM,
    /** 短期压缩摘要。 */
    SHORT_TERM_SUMMARY,
    /** 长期记忆引用。 */
    LONG_TERM_MEMORY,
    /** 工作区事实。 */
    WORKSPACE_FACT,
    /** 工具、Skill 或 MCP 的能力目录摘要。 */
    CAPABILITY
}
