package com.wzx.babiq.server.business.oa.session;

import java.time.Instant;

/** Immutable server-issued lease used to authorize one desktop WebSocket generation. */
public record ReadyOaSessionLease(
        String authSessionId,
        String desktopInstanceId,
        String desktopSessionId,
        String webSocketSessionId,
        String userId,
        String tenantId,
        String platformId,
        long generation,
        String activeCredentialRef,
        int credentialVersion,
        Instant capturedAt
) {
    public ReadyOaSessionLease {
        requireText(authSessionId, "authSessionId");
        requireText(desktopInstanceId, "desktopInstanceId");
        requireText(desktopSessionId, "desktopSessionId");
        requireText(webSocketSessionId, "webSocketSessionId");
        requireText(userId, "userId");
        requireText(tenantId, "tenantId");
        requireText(platformId, "platformId");
        requireText(activeCredentialRef, "activeCredentialRef");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        if (credentialVersion < 1) throw new IllegalArgumentException("credentialVersion must be positive");
        if (capturedAt == null) throw new IllegalArgumentException("capturedAt must not be null");
    }

    @Override
    public String toString() {
        return "ReadyOaSessionLease(authSessionId=[REDACTED], desktopInstanceId=[REDACTED], "
                + "desktopSessionId=[REDACTED], webSocketSessionId=[REDACTED], userId=" + userId
                + ", tenantId=" + tenantId + ", platformId=" + platformId + ", generation=" + generation
                + ", credentialVersion=" + credentialVersion + ")";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
