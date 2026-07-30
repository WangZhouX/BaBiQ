package com.wzx.babiq.server.business.upload;

import java.time.Instant;

/** Durable metadata for an opaque resource handle; storageRef is never a remote URL. */
public record BusinessResourceHandleRecord(
        String handleId,
        String desktopInstanceId,
        String desktopSessionId,
        String authSessionId,
        String tenantId,
        long identityGeneration,
        String mediaType,
        long contentLength,
        String storageRef,
        String policy,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt) {

    @Override public String toString() {
        return "BusinessResourceHandleRecord(handleId=[REDACTED], mediaType=" + mediaType
                + ", contentLength=" + contentLength + ", storageRef=[REDACTED])";
    }
}
