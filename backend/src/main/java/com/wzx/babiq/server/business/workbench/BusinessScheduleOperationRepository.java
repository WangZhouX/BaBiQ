package com.wzx.babiq.server.business.workbench;

import java.time.Instant;

/** Durable CAS boundary for non-replayable OA schedule creation. */
public interface BusinessScheduleOperationRepository {
    Claim claim(Request request, Instant now);
    boolean complete(String operationId, String requestFingerprint, long resultRevision, Instant now);
    boolean markOutcomeUnknown(String operationId, String requestFingerprint, Instant now);
    boolean markFailed(String operationId, String requestFingerprint, Instant now);
    int recoverInFlight(Instant now);

    enum Decision { WON, COMPLETED, IN_FLIGHT, OUTCOME_UNKNOWN, CONFLICT }
    enum State { IN_FLIGHT, COMPLETED, OUTCOME_UNKNOWN, FAILED }

    record Request(
            String operationId,
            String desktopInstanceId,
            String desktopSessionId,
            String authSessionId,
            String tenantId,
            long identityGeneration,
            String clientOperationId,
            String actorUserId,
            long formRevision,
            String attachmentBatchId,
            String requestFingerprint) {
    }

    record Record(String operationId, String requestFingerprint, State state, Long resultRevision) {
    }

    record Claim(Decision decision, Record record) {
    }
}
