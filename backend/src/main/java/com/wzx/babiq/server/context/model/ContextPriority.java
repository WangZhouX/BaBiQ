package com.wzx.babiq.server.context.model;

/**
 * 上下文分层优先级。
 *
 * <p>该枚举用于显式告诉模型和 UI：哪些内容是本轮指令，哪些只是历史或记忆参考。
 * P3 的核心边界是 current turn 永远不能被历史记录或长期记忆覆盖。</p>
 */
public enum ContextPriority {
    /** 本轮用户消息和运行策略，代表当前任务的最高事实源。 */
    AUTHORITATIVE,
    /** 近期未压缩历史，可信但不能覆盖本轮新指令。 */
    HIGH,
    /** 短期摘要等压缩内容，只代表被压缩区间。 */
    MEDIUM,
    /** 长期记忆、工作区事实、能力目录等参考材料。 */
    REFERENCE,
    /** 明确不进入模型的内容，仅保留在快照中解释原因。 */
    EXCLUDED
}
