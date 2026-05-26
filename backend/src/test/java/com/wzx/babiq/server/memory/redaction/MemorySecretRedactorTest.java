package com.wzx.babiq.server.memory.redaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆脱敏器测试。
 *
 * <p>P3-4 的长期记忆会跨会话保留，任何 API Key、Bearer token 或私钥都不能进入 Phase2 归并。
 * 这里先用明确规则约束脱敏结果，再让抽取流水线复用同一套判断。</p>
 */
class MemorySecretRedactorTest {

    @Test
    @DisplayName("普通 API Key 会被替换但单次命中仍可进入 CLEAN 候选")
    void redact_should_mask_single_api_key_and_keep_clean_candidate() {
        MemorySecretRedactor redactor = new MemorySecretRedactor();

        MemorySecretRedactionResult result = redactor.redact("请记住 api_key=sk-1234567890abcdef");

        assertThat(result.redactedText()).contains("[REDACTED:api-key]");
        assertThat(result.redactedText()).doesNotContain("sk-1234567890abcdef");
        assertThat(result.redactionCount()).isEqualTo(1);
        assertThat(result.pollutionStatus()).isEqualTo(MemoryPollutionStatus.CLEAN);
    }

    @Test
    @DisplayName("三次以上密钥命中会标记 SECRET_RISK 并阻止 Phase2 归并")
    void redact_should_mark_secret_risk_when_secret_hits_are_frequent() {
        MemorySecretRedactor redactor = new MemorySecretRedactor();

        MemorySecretRedactionResult result = redactor.redact("""
                api_key=sk-first-token
                Authorization: Bearer second-secret-token
                token=third-secret-token
                """);

        assertThat(result.redactionCount()).isGreaterThanOrEqualTo(3);
        assertThat(result.pollutionStatus()).isEqualTo(MemoryPollutionStatus.SECRET_RISK);
    }

    @Test
    @DisplayName("私钥命中无论次数多少都标记 SECRET_RISK")
    void redact_should_mark_secret_risk_for_private_key() {
        MemorySecretRedactor redactor = new MemorySecretRedactor();

        MemorySecretRedactionResult result = redactor.redact("""
                -----BEGIN PRIVATE KEY-----
                abcdef
                -----END PRIVATE KEY-----
                """);

        assertThat(result.privateKeyHit()).isTrue();
        assertThat(result.redactedText()).contains("[REDACTED:private-key]");
        assertThat(result.pollutionStatus()).isEqualTo(MemoryPollutionStatus.SECRET_RISK);
    }
}
