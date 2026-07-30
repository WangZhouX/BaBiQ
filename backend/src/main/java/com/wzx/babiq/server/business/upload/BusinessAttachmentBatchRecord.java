package com.wzx.babiq.server.business.upload;

import java.time.Instant;

/** Durable, non-secret attachment batch metadata. OA file ids are represented only by a SecretStore reference. */
public record BusinessAttachmentBatchRecord(
        String batchId,
        String desktopInstanceId,
        String desktopSessionId,
        String authSessionId,
        String tenantId,
        long identityGeneration,
        String operation,
        String clientOperationId,
        String actorUserId,
        String scope,
        String teamId,
        String scheduleTypeId,
        String parentRelationType,
        String parentResourceId,
        String parentRecordId,
        String formRevision,
        String declarationSecretRef,
        BusinessAttachmentTicketService.BatchStatus state,
        String fileIdSecretRef,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public BusinessAttachmentBatchRecord(String batchId, String desktopInstanceId, String desktopSessionId,
                                         String authSessionId, String tenantId, long identityGeneration,
                                         String operation, String clientOperationId, String parentResourceId,
                                         String formRevision, BusinessAttachmentTicketService.BatchStatus state,
                                         String fileIdSecretRef, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        this(batchId, desktopInstanceId, desktopSessionId, authSessionId, tenantId, identityGeneration,
                operation, clientOperationId, "legacy-actor", "PERSONAL", null, "legacy-type",
                "CASE", parentResourceId, null, formRevision, "keystore://legacy-declaration", state, fileIdSecretRef,
                expiresAt, createdAt, updatedAt);
    }

    public BusinessAttachmentBatchRecord(String batchId, String desktopInstanceId, String desktopSessionId,
                                         String authSessionId, String tenantId, long identityGeneration,
                                         String operation, String clientOperationId, String actorUserId,
                                         String scope, String teamId, String scheduleTypeId,
                                         String parentRelationType, String parentResourceId, String formRevision,
                                         String declarationSecretRef,
                                         BusinessAttachmentTicketService.BatchStatus state,
                                         String fileIdSecretRef, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        this(batchId, desktopInstanceId, desktopSessionId, authSessionId, tenantId, identityGeneration,
                operation, clientOperationId, actorUserId, scope, teamId, scheduleTypeId,
                parentRelationType, parentResourceId, null, formRevision, declarationSecretRef,
                state, fileIdSecretRef, expiresAt, createdAt, updatedAt);
    }

    @Override public String toString() {
        return "BusinessAttachmentBatchRecord(batchId=[REDACTED], state=" + state
                + ", fileIdSecretRef=[REDACTED])";
    }
}
