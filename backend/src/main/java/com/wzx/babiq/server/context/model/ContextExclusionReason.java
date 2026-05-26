package com.wzx.babiq.server.context.model;

/**
 * 上下文条目被排除的原因。
 *
 * <p>P3 需要把“未注入模型”的原因也写进 snapshot，避免排查时只能猜测 token 裁剪或污染过滤行为。</p>
 */
public enum ContextExclusionReason {
    /** TurnSummary、运行反馈等只服务 UI 或观测，不应作为模型事实。 */
    RUNTIME_SUMMARY,
    /** ContextCompactionItem 是压缩事件标记，不是摘要正文。 */
    COMPACTION_MARKER,
    /** Agent 流式增量没有完整文本，直接注入会污染历史语义。 */
    INCOMPLETE_ASSISTANT_MESSAGE,
    /** 文本为空，没有模型可见价值。 */
    EMPTY_TEXT,
    /** 预留给后续 token budget 裁剪。 */
    TOKEN_BUDGET
}
