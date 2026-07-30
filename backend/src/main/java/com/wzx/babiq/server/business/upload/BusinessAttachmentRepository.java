package com.wzx.babiq.server.business.upload;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/** SQLite-backed CAS boundary for attachment tickets, batches, and resource handles. */
public interface BusinessAttachmentRepository {
    void create(BusinessAttachmentBatchRecord batch, BusinessAttachmentTicketRecord ticket);

    Optional<BusinessAttachmentBatchRecord> findBatch(String batchId);

    Optional<BusinessAttachmentTicketRecord> findTicketByBatchId(String batchId);

    boolean claimUpload(String batchId, String ticketDigest, String desktopInstanceId,
                        String desktopSessionId, String authSessionId, String tenantId,
                        long generation, Instant now);

    boolean completeUpload(String batchId, String ticketDigest, String fileIdSecretRef, Instant now);

    boolean transitionUpload(String batchId, String ticketDigest,
                             BusinessAttachmentTicketService.TicketStatus expectedTicket,
                             BusinessAttachmentTicketService.TicketStatus nextTicket,
                             BusinessAttachmentTicketService.BatchStatus expectedBatch,
                             BusinessAttachmentTicketService.BatchStatus nextBatch,
                             Instant now);

    boolean beginScheduleConsumption(String batchId, String desktopInstanceId, String desktopSessionId,
                                     String authSessionId, String tenantId, long generation,
                                     String clientOperationId, String actorUserId,
                                     String scope, String teamId, String scheduleTypeId,
                                     String parentRelationType, String parentResourceId,
                                     String parentRecordId,
                                     String formRevision, Instant now);

    default boolean beginScheduleConsumption(String batchId, String desktopInstanceId, String desktopSessionId,
                                             String authSessionId, String tenantId, long generation,
                                             String clientOperationId, String parentResourceId,
                                             String formRevision, Instant now) {
        return beginScheduleConsumption(batchId, desktopInstanceId, desktopSessionId, authSessionId,
                tenantId, generation, clientOperationId, "legacy-actor",
                "PERSONAL", null, "legacy-type", "CASE",
                parentResourceId, null, formRevision, now);
    }

    boolean finishScheduleConsumption(String batchId,
                                      BusinessAttachmentTicketService.BatchStatus nextState,
                                      Instant now);

    int revoke(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation,
            Instant now);

    List<String> fileIdSecretRefsForConnection(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation);
    List<String> declarationSecretRefsForConnection(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation);

    List<String> recoverableFileIdSecretRefs(Instant now);
    List<String> recoverableDeclarationSecretRefs(Instant now);

    RecoveryCounts recover(Instant now);

    void insertResource(BusinessResourceHandleRecord record);

    Optional<BusinessResourceHandleRecord> findResource(String handleId);

    default List<String> resourceStorageRefsForConnection(
            String desktopInstanceId, String desktopSessionId, long generation) {
        return List.of();
    }

    default List<String> expiredResourceStorageRefs(Instant now) {
        return List.of();
    }

    default List<String> pendingResourceCleanupRefs() {
        return List.of();
    }

    default List<String> allResourceStorageRefs() {
        return List.of();
    }

    default boolean completeResourceCleanup(String storageRef) {
        return false;
    }

    int revokeResources(
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            long generation,
            Instant now);

    int purgeExpiredResources(Instant now);

    record RecoveryCounts(int unknownTickets, int unknownBatches, int expiredTickets,
                          int revokedBatches, int revokedResources) { }
}
