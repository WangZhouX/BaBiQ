package com.wzx.babiq.server.business.upload;

import java.time.Instant;
import java.util.List;

public interface BusinessAttachmentSecretCleanupRepository {
    void upsertPending(String secretRef, String secretKind, String reasonCode, Instant now);
    void recordFailure(String secretRef, Instant now);
    List<PendingSecret> listPending(int limit);
    void deleteTombstone(String secretRef);

    record PendingSecret(String secretRef, String secretKind, String reasonCode, int attemptCount) {
        @Override public String toString() {
            return "PendingSecret(secretRef=[REDACTED], secretKind=" + secretKind
                    + ", reasonCode=" + reasonCode + ", attemptCount=" + attemptCount + ")";
        }
    }
}
