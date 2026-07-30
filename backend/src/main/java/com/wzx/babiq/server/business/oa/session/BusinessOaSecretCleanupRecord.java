package com.wzx.babiq.server.business.oa.session;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/** OA SecretStore 引用的非敏感耐久清理元数据。 */
public record BusinessOaSecretCleanupRecord(
        @JsonIgnore String secretRef,
        String authSessionId,
        BusinessOaSecretCleanupState state,
        String reasonCode,
        String operationId,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        Instant lastAttemptAt,
        String lastResultCode) {

    /** 避免日志或断言失败通过 record 默认 toString 暴露 SecretStore 引用。 */
    @Override
    public String toString() {
        return "BusinessOaSecretCleanupRecord[authSessionId=" + authSessionId
                + ", state=" + state
                + ", reasonCode=" + reasonCode
                + ", operationId=" + operationId
                + ", attemptCount=" + attemptCount
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + ", lastAttemptAt=" + lastAttemptAt
                + ", lastResultCode=" + lastResultCode + "]";
    }
}
