package com.wzx.babiq.server.application.auth;

import java.time.Instant;
import java.util.Objects;

/** Server-owned installation identity shared by provisional identity, catalog and page-context projections. */
public record ApplicationInstallationLease(
        String installationId,
        TrustedDesktopConnection owner,
        long targetGeneration,
        Instant expiresAt
) {
    public ApplicationInstallationLease {
        if (installationId == null || installationId.isBlank()) {
            throw new IllegalArgumentException("installationId must not be blank");
        }
        Objects.requireNonNull(owner, "owner");
        if (targetGeneration < 0) {
            throw new IllegalArgumentException("targetGeneration must not be negative");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public void requireOwner(TrustedDesktopConnection connection) {
        if (!owner.equals(Objects.requireNonNull(connection, "connection"))) {
            throw new IllegalStateException("Application installation lease owner mismatch");
        }
    }

    @Override
    public String toString() {
        return "ApplicationInstallationLease(installationId=[REDACTED], owner=" + owner
                + ", targetGeneration=" + targetGeneration + ", expiresAt=" + expiresAt + ")";
    }
}
