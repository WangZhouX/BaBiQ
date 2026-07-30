package com.wzx.babiq.server.business.oa.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Closes durable OA sessions interrupted during authentication, restore, installation or revocation. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessOaSessionRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(BusinessOaSessionRecoveryService.class);

    private final OaSessionRepository repository;
    private final OaSessionPersistenceService persistence;

    public BusinessOaSessionRecoveryService(OaSessionRepository repository,
                                             OaSessionPersistenceService persistence) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    /**
     * Recovery is fail-soft per session: one stale CAS row must not leave other staged secrets
     * behind. The persistence service performs the credential deletion and generation CAS.
     */
    public RecoveryReport recover() {
        int scanned = 0;
        int recovered = 0;
        int failed = 0;
        for (OaSessionRecord record : repository.listRecoverable()) {
            scanned++;
            try {
                persistence.recoverInstallingBeforeCleanup(record);
                recovered++;
            } catch (RuntimeException failure) {
                failed++;
                log.warn("OA session recovery failed: phase={}, reasonType={}",
                        record.phase(), failure.getClass().getSimpleName());
            }
        }
        persistence.reconcileOrphanReservedCredentials();
        try {
            persistence.drainPendingCredentialCleanup();
        } catch (RuntimeException failure) {
            log.warn("OA credential recovery cleanup failed: reasonType={}",
                    failure.getClass().getSimpleName());
        }
        return new RecoveryReport(scanned, recovered, failed);
    }

    public record RecoveryReport(int scanned, int recovered, int failed) {
    }
}
