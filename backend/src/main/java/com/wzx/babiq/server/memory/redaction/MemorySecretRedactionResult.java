package com.wzx.babiq.server.memory.redaction;

/**
 * 长期记忆脱敏结果。
 *
 * @param redactedText 已替换敏感片段的文本，后续 Phase1/Phase2 只能读取这个字段
 * @param redactionCount 本次命中的敏感片段数量，超过阈值时升级为 SECRET_RISK
 * @param privateKeyHit 是否命中过私钥块，私钥一旦命中就必须阻止归并
 * @param pollutionStatus 候选污染状态，决定是否允许进入 Phase2
 */
public record MemorySecretRedactionResult(
        String redactedText,
        int redactionCount,
        boolean privateKeyHit,
        MemoryPollutionStatus pollutionStatus
) {
}
