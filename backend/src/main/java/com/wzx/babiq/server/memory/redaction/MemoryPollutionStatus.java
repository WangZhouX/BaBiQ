package com.wzx.babiq.server.memory.redaction;

/**
 * 长期记忆候选的污染状态。
 *
 * <p>Phase1 抽取后的内容只有 CLEAN 才能进入 Phase2 归并；SECRET_RISK 会保留审计，
 * 但不会写入长期记忆产物，避免 API Key 或私钥跨会话扩散。</p>
 */
public enum MemoryPollutionStatus {
    /** 通过脱敏检查，可以作为长期记忆候选参与归并。 */
    CLEAN,
    /** 命中高风险密钥规则，只能保留审计，不能参与 Phase2。 */
    SECRET_RISK
}
