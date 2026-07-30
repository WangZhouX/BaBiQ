package com.wzx.babiq.server.business.upload;

import java.time.Instant;

/** Durable upload-ticket metadata. ticketDigest is a one-way SHA-256 digest, never the bearer ticket. */
public record BusinessAttachmentTicketRecord(
        String ticketDigest,
        String batchId,
        String desktopInstanceId,
        String desktopSessionId,
        String authSessionId,
        String tenantId,
        long identityGeneration,
        BusinessAttachmentTicketService.TicketStatus state,
        Instant expiresAt,
        Instant claimedAt,
        Instant completedAt,
        Instant updatedAt) {

    @Override public String toString() {
        return "BusinessAttachmentTicketRecord(ticketDigest=[REDACTED], batchId=[REDACTED], state=" + state + ")";
    }
}
