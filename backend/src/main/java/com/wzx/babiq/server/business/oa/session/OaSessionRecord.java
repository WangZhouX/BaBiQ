package com.wzx.babiq.server.business.oa.session;

import java.time.Instant;

/** OA 会话的非敏感持久化索引；access/refresh token 永不进入此对象。 */
public record OaSessionRecord(
        String authSessionId,
        String desktopInstanceId,
        String desktopSessionId,
        String userId,
        String tenantId,
        String platformId,
        OaSessionPhase phase,
        long generation,
        String activeCredentialRef,
        String stagedCredentialRef,
        int credentialVersion,
        Instant installStartedAt,
        Instant installedAt,
        Instant detachedAt,
        Instant revokedAt,
        Instant updatedAt,
        String installationId,
        String installationOwnerDesktopInstanceId,
        String installationOwnerDesktopSessionId,
        long installationTargetGeneration,
        Instant installationExpiresAt
) {
    public OaSessionRecord {
        if (authSessionId == null || authSessionId.isBlank()) throw new IllegalArgumentException("authSessionId must not be blank");
        if (desktopInstanceId == null || desktopInstanceId.isBlank()) throw new IllegalArgumentException("desktopInstanceId must not be blank");
        if (desktopSessionId == null || desktopSessionId.isBlank()) throw new IllegalArgumentException("desktopSessionId must not be blank");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        if (phase == null) throw new IllegalArgumentException("phase must not be null");
        if (installationTargetGeneration < 0) {
            throw new IllegalArgumentException("installationTargetGeneration must not be negative");
        }
        if (installationId == null && (installationOwnerDesktopInstanceId != null
                || installationOwnerDesktopSessionId != null || installationExpiresAt != null)) {
            throw new IllegalArgumentException("installation metadata requires installationId");
        }
    }

    /** Compatibility constructor for records created before installation leases were persisted. */
    public OaSessionRecord(String authSessionId, String desktopInstanceId, String desktopSessionId,
                           String userId, String tenantId, String platformId, OaSessionPhase phase,
                           long generation, String activeCredentialRef, String stagedCredentialRef,
                           int credentialVersion, Instant installStartedAt, Instant installedAt,
                           Instant detachedAt, Instant revokedAt, Instant updatedAt) {
        this(authSessionId, desktopInstanceId, desktopSessionId, userId, tenantId, platformId, phase,
                generation, activeCredentialRef, stagedCredentialRef, credentialVersion, installStartedAt,
                installedAt, detachedAt, revokedAt, updatedAt, null, null, null, 0, null);
    }

    public static OaSessionRecord signedOut(String authSessionId, String desktopInstanceId,
                                            String desktopSessionId, Instant now) {
        return new OaSessionRecord(authSessionId, desktopInstanceId, desktopSessionId,
                null, null, null, OaSessionPhase.SIGNED_OUT, 0, null, null, 0,
                null, null, null, null, now, null, null, null, 0, null);
    }

    public static OaSessionRecord ready(String authSessionId, String desktopInstanceId,
                                        String desktopSessionId, String activeCredentialRef, Instant now) {
        return new OaSessionRecord(authSessionId, desktopInstanceId, desktopSessionId,
                "user", "tenant", "platform", OaSessionPhase.READY, 1,
                activeCredentialRef, null, 1, null, now, null, null, now,
                null, null, null, 0, null);
    }
}
