package com.wzx.babiq.server.business.upload;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

/** Durable retry boundary for terminal deletion of attachment SecretStore material. */
@Service
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public class BusinessAttachmentSecretCleanupService {
    private static final int BATCH_SIZE = 100;
    private final BusinessAttachmentSecretCleanupRepository repository;
    private final BusinessAttachmentFileIdStore secrets;
    private final Clock clock;

    @Autowired
    public BusinessAttachmentSecretCleanupService(
            BusinessAttachmentSecretCleanupRepository repository,
            BusinessAttachmentFileIdStore secrets) {
        this(repository, secrets, Clock.systemUTC());
    }

    BusinessAttachmentSecretCleanupService(
            BusinessAttachmentSecretCleanupRepository repository,
            BusinessAttachmentFileIdStore secrets,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void scheduleAndAttempt(String secretRef, String secretKind, String reasonCode) {
        if (secretRef == null || secretRef.isBlank()) return;
        repository.upsertPending(secretRef, secretKind, reasonCode, clock.instant());
        attempt(secretRef);
    }

    public DrainReport drainPending() {
        var pending = repository.listPending(BATCH_SIZE);
        int deleted = 0;
        int failed = 0;
        for (var item : pending) {
            if (attempt(item.secretRef())) deleted++;
            else failed++;
        }
        return new DrainReport(pending.size(), deleted, failed);
    }

    private boolean attempt(String secretRef) {
        try {
            secrets.delete(secretRef);
            repository.deleteTombstone(secretRef);
            return true;
        } catch (RuntimeException ignored) {
            repository.recordFailure(secretRef, clock.instant());
            return false;
        }
    }

    public record DrainReport(int scanned, int deleted, int failed) {
        public DrainReport {
            if (scanned < 0 || deleted < 0 || failed < 0 || deleted + failed != scanned) {
                throw new IllegalArgumentException("invalid attachment cleanup report");
            }
        }
    }
}
