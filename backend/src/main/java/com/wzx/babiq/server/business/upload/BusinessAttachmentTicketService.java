package com.wzx.babiq.server.business.upload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Server-owned, single-use upload ticket state machine.  This class validates the declaration/metadata
 * contract; the HTTP streaming controller is responsible for feeding it metadata after writing a bounded
 * owner-only temporary file and sniffing the actual bytes.
 */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessAttachmentTicketService {
    public static final Duration TICKET_TTL = Duration.ofSeconds(60);
    public static final long MAX_SINGLE_BYTES = 20_000_000L;
    public static final long MAX_TOTAL_BYTES = 500_000_000L;
    public static final int MAX_FILE_COUNT = 50;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> EXTENSION_MEDIA_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("pdf", "application/pdf"), Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("mp4", "video/mp4"), Map.entry("avi", "video/x-msvideo"), Map.entry("mov", "video/quicktime"),
            Map.entry("mkv", "video/x-matroska"), Map.entry("webm", "video/webm"));

    private final Clock clock;
    private final BusinessAttachmentRepository repository;
    private final BusinessAttachmentFileIdStore fileIdStore;
    private final BusinessAttachmentSecretCleanupService secretCleanup;
    private final Map<String, Entry> batches = new HashMap<>();

    public BusinessAttachmentTicketService() {
        this(Clock.systemUTC());
    }

    public BusinessAttachmentTicketService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.repository = null;
        this.fileIdStore = null;
        this.secretCleanup = null;
    }

    public BusinessAttachmentTicketService(BusinessAttachmentRepository repository,
                                           BusinessAttachmentFileIdStore fileIdStore) {
        this.clock = Clock.systemUTC();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fileIdStore = Objects.requireNonNull(fileIdStore, "fileIdStore");
        this.secretCleanup = null;
    }

    @Autowired
    public BusinessAttachmentTicketService(BusinessAttachmentRepository repository,
                                           BusinessAttachmentFileIdStore fileIdStore,
                                           BusinessAttachmentSecretCleanupService secretCleanup) {
        this.clock = Clock.systemUTC();
        this.repository = Objects.requireNonNull(repository, "repository");
        this.fileIdStore = Objects.requireNonNull(fileIdStore, "fileIdStore");
        this.secretCleanup = Objects.requireNonNull(secretCleanup, "secretCleanup");
    }

    public synchronized PreparedBatch prepare(TrustedDesktopConnection connection,
                                               ReadyOaSessionLease lease,
                                               String operation,
                                               String clientOperationId,
                                               String parentResourceId,
                                               List<FileDeclaration> declarations) {
        return prepare(connection, lease, operation, clientOperationId, lease.userId(), "PERSONAL",
                null, "legacy-type", "CASE", parentResourceId, null, "0", declarations);
    }

    public synchronized PreparedBatch prepare(TrustedDesktopConnection connection,
                                               ReadyOaSessionLease lease,
                                               String operation,
                                               String clientOperationId,
                                               String actorUserId,
                                               String scope,
                                               String teamId,
                                               String scheduleTypeId,
                                               String parentRelationType,
                                               String parentResourceId,
                                               String formRevision,
                                               List<FileDeclaration> declarations) {
        return prepare(connection, lease, operation, clientOperationId, actorUserId, scope, teamId,
                scheduleTypeId, parentRelationType, parentResourceId, null, formRevision, declarations);
    }

    public synchronized PreparedBatch prepare(TrustedDesktopConnection connection,
                                               ReadyOaSessionLease lease,
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
                                               List<FileDeclaration> declarations) {
        requireBinding(connection, lease);
        if (!"SCHEDULE_CREATE".equals(operation)) throw new IllegalArgumentException("unsupported attachment operation");
        requireText(clientOperationId, "clientOperationId");
        if (!lease.userId().equals(actorUserId)) throw new IllegalArgumentException("attachment actor does not match READY identity");
        if (!Set.of("PERSONAL", "TEAM").contains(scope)) throw new IllegalArgumentException("invalid attachment scope");
        if ("TEAM".equals(scope)) requireText(teamId, "teamId");
        else if (teamId != null) throw new IllegalArgumentException("personal attachment must not include teamId");
        requireText(scheduleTypeId, "scheduleTypeId");
        if (!Set.of("CASE", "CUSTOMER", "VISIT", "SERVICE").contains(parentRelationType)) {
            throw new IllegalArgumentException("invalid parent relation type");
        }
        requireText(parentResourceId, "parentResourceId");
        if ("SERVICE".equals(parentRelationType)) requireText(parentRecordId, "parentRecordId");
        else if (parentRecordId != null) throw new IllegalArgumentException(
                "non-service attachment must not include parentRecordId");
        requireText(formRevision, "formRevision");
        List<FileDeclaration> files = normalizeDeclarations(declarations);
        String batchId = opaqueId();
        String ticket = opaqueId();
        Instant expiresAt = clock.instant().plus(TICKET_TTL);
        Entry entry = new Entry(batchId, ticket, connection, lease, operation, clientOperationId,
                actorUserId, scope, teamId, scheduleTypeId, parentRelationType,
                parentResourceId, parentRecordId, files, expiresAt,
                TicketStatus.ISSUED, BatchStatus.PENDING);
        if (repository != null) {
            Instant now = clock.instant();
            String declarationRef = fileIdStore.saveDeclarations(batchId,
                    writeDeclarations(files).toCharArray());
            try {
                repository.create(
                        new BusinessAttachmentBatchRecord(batchId, connection.desktopInstanceId(),
                            connection.desktopSessionId(), lease.authSessionId(), lease.tenantId(),
                            lease.generation(), operation, clientOperationId, actorUserId, scope, teamId,
                            scheduleTypeId, parentRelationType, parentResourceId, parentRecordId, formRevision,
                            declarationRef,
                            BatchStatus.PENDING, null, expiresAt, now, now),
                        new BusinessAttachmentTicketRecord(ticketDigest(ticket), batchId,
                            connection.desktopInstanceId(), connection.desktopSessionId(),
                            lease.authSessionId(), lease.tenantId(), lease.generation(),
                            TicketStatus.ISSUED, expiresAt, null, null, now));
            } catch (RuntimeException failure) {
                cleanupSecret(declarationRef, "DECLARATION", "PREPARE_ROLLBACK");
                throw failure;
            }
        }
        if (repository == null) batches.put(batchId, entry);
        return new PreparedBatch(batchId, ticket, expiresAt);
    }

    public synchronized UploadClaim claim(String batchId, String ticket,
                                           TrustedDesktopConnection connection,
                                           ReadyOaSessionLease lease) {
        return tryClaim(batchId, ticket, connection, lease)
                .orElseThrow(() -> new TicketUnavailableException("attachment upload ticket is unavailable"));
    }

    /** CAS-like single-use claim.  All binding and expiry failures are intentionally indistinguishable. */
    public synchronized Optional<UploadClaim> tryClaim(String batchId, String ticket,
                                                        TrustedDesktopConnection connection,
                                                        ReadyOaSessionLease lease) {
        if (repository != null) {
            if (batchId == null || ticket == null || connection == null || lease == null) return Optional.empty();
            BusinessAttachmentBatchRecord batch = repository.findBatch(batchId).orElse(null);
            BusinessAttachmentTicketRecord durableTicket = repository.findTicketByBatchId(batchId).orElse(null);
            if (batch == null || durableTicket == null || !batch.expiresAt().isAfter(clock.instant())
                    || batch.state() != BatchStatus.PENDING
                    || durableTicket.state() != TicketStatus.ISSUED
                    || !constantTimeEquals(durableTicket.ticketDigest(), ticketDigest(ticket))
                    || !matches(batch, connection, lease)) return Optional.empty();
            if (!repository.claimUpload(batchId, ticketDigest(ticket), connection.desktopInstanceId(),
                    connection.desktopSessionId(), lease.authSessionId(), lease.tenantId(),
                    lease.generation(), clock.instant())) return Optional.empty();
            char[] declarations = fileIdStore.loadDeclarations(batch.declarationSecretRef());
            try {
                return Optional.of(new UploadClaim(batchId, ticket, connection, lease,
                        readDeclarations(new String(declarations))));
            } finally {
                java.util.Arrays.fill(declarations, '\0');
            }
        }
        Entry entry = batches.get(batchId);
        if (entry == null) return Optional.empty();
        if (isExpired(entry)) {
            transitionExpired(entry);
            return Optional.empty();
        }
        if (!constantTimeEquals(entry.ticket(), ticket) || !matches(entry.connection(), connection)
                || !matches(entry.lease(), lease) || entry.ticketStatus() != TicketStatus.ISSUED) {
            return Optional.empty();
        }
        if (repository != null && !repository.claimUpload(batchId, ticketDigest(ticket),
                connection.desktopInstanceId(), connection.desktopSessionId(), lease.authSessionId(),
                lease.tenantId(), lease.generation(), clock.instant())) {
            return Optional.empty();
        }
        batches.put(batchId, entry.with(TicketStatus.CLAIMED, BatchStatus.PENDING));
        Entry claimed = entry.with(TicketStatus.IN_FLIGHT, BatchStatus.PENDING);
        batches.put(batchId, claimed);
        return Optional.of(new UploadClaim(batchId, ticket, connection, lease, entry.declarations()));
    }

    public synchronized UploadReceipt complete(UploadClaim claim, List<UploadedFile> uploadedFiles) {
        return complete(claim, uploadedFiles, null);
    }

    public synchronized UploadReceipt complete(UploadClaim claim, List<UploadedFile> uploadedFiles,
                                               BusinessAttachmentRemoteUploader.UploadedRemoteFiles remoteFiles) {
        Objects.requireNonNull(claim, "claim");
        if (repository != null) {
            return completeDurable(claim, uploadedFiles, remoteFiles);
        }
        Entry entry = batches.get(claim.batchId());
        if (entry == null || isExpired(entry) || entry.ticketStatus() != TicketStatus.IN_FLIGHT
                || !constantTimeEquals(entry.ticket(), claim.ticket())
                || !matches(entry.connection(), claim.connection()) || !matches(entry.lease(), claim.lease())) {
            if (entry != null && entry.ticketStatus() == TicketStatus.IN_FLIGHT) {
                // complete() runs after the OA call; a stale lease or timeout cannot prove whether bytes arrived.
                batches.put(entry.batchId(), entry.with(TicketStatus.OUTCOME_UNKNOWN, BatchStatus.OUTCOME_UNKNOWN));
            }
            throw new TicketRejectedException("attachment upload outcome is unknown");
        }
        try {
            validateUploaded(entry.declarations(), uploadedFiles);
        } catch (RuntimeException failure) {
            transition(entry, TicketStatus.REJECTED, BatchStatus.FAILED);
            throw new TicketRejectedException("attachment upload declaration was rejected");
        }
        String secretRef = null;
        try {
            if (repository != null) {
                if (remoteFiles == null || remoteFiles.fileCount() != uploadedFiles.size()) {
                    throw new TicketRejectedException("attachment upload outcome is unknown");
                }
                List<char[]> fileIds = remoteFiles.copyFileIds();
                try {
                    secretRef = fileIdStore.save(entry.batchId(), fileIds);
                } finally {
                    fileIds.forEach(value -> java.util.Arrays.fill(value, '\0'));
                }
                if (!repository.completeUpload(entry.batchId(), ticketDigest(entry.ticket()), secretRef, clock.instant())) {
                    cleanupSecret(secretRef, "FILE_IDS", "ATTACHMENT_OUTCOME_UNKNOWN");
                    throw new TicketRejectedException("attachment upload outcome is unknown");
                }
            }
            batches.put(entry.batchId(), entry.with(TicketStatus.SUCCEEDED, BatchStatus.READY));
        } catch (RuntimeException failure) {
            if (secretRef != null) {
                cleanupSecret(secretRef, "FILE_IDS", "ATTACHMENT_OUTCOME_UNKNOWN");
            }
            transition(entry, TicketStatus.OUTCOME_UNKNOWN, BatchStatus.OUTCOME_UNKNOWN);
            throw failure;
        }
        return new UploadReceipt(entry.batchId(), uploadedFiles.size());
    }

    /** Validates actual file metadata before any remote bytes are sent. */
    public synchronized void validateBeforeRemote(UploadClaim claim, List<UploadedFile> uploadedFiles) {
        Objects.requireNonNull(claim, "claim");
        if (repository != null) {
            BusinessAttachmentTicketRecord ticket = repository.findTicketByBatchId(claim.batchId()).orElse(null);
            BusinessAttachmentBatchRecord batch = repository.findBatch(claim.batchId()).orElse(null);
            if (ticket == null || batch == null || ticket.state() != TicketStatus.IN_FLIGHT
                    || batch.state() != BatchStatus.PENDING || !batch.expiresAt().isAfter(clock.instant())
                    || !constantTimeEquals(ticket.ticketDigest(), ticketDigest(claim.ticket()))
                    || !matches(batch, claim.connection(), claim.lease())) {
                throw new TicketRejectedException("attachment upload declaration was rejected");
            }
            try {
                validateUploaded(claim.declarations(), uploadedFiles);
                return;
            } catch (RuntimeException failure) {
                transitionDurable(claim, TicketStatus.REJECTED, BatchStatus.FAILED);
                throw new TicketRejectedException("attachment upload declaration was rejected");
            }
        }
        Entry entry = batches.get(claim.batchId());
        if (entry == null || isExpired(entry) || entry.ticketStatus() != TicketStatus.IN_FLIGHT
                || !constantTimeEquals(entry.ticket(), claim.ticket())
                || !matches(entry.connection(), claim.connection()) || !matches(entry.lease(), claim.lease())) {
            throw new TicketRejectedException("attachment upload declaration was rejected");
        }
        try {
            validateUploaded(entry.declarations(), uploadedFiles);
        } catch (RuntimeException failure) {
            transition(entry, TicketStatus.REJECTED, BatchStatus.FAILED);
            throw new TicketRejectedException("attachment upload declaration was rejected");
        }
    }

    /** Marks a claimed upload as locally rejected (for example a MIME or size mismatch). */
    public synchronized void reject(UploadClaim claim) {
        transitionClaim(claim, TicketStatus.REJECTED, BatchStatus.FAILED);
    }

    /** Marks a claimed upload outcome unknown when remote OA may have received bytes. */
    public synchronized void outcomeUnknown(UploadClaim claim) {
        transitionClaim(claim, TicketStatus.OUTCOME_UNKNOWN, BatchStatus.OUTCOME_UNKNOWN);
    }

    public synchronized Status status(String batchId) {
        if (repository != null) {
            var ticket = repository.findTicketByBatchId(batchId)
                    .orElseThrow(() -> new TicketUnavailableException("attachment upload batch is unavailable"));
            var batch = repository.findBatch(batchId)
                    .orElseThrow(() -> new TicketUnavailableException("attachment upload batch is unavailable"));
            return new Status(ticket.state(), batch.state());
        }
        Entry entry = batches.get(batchId);
        if (entry == null) throw new TicketUnavailableException("attachment upload batch is unavailable");
        if (isExpired(entry)) transitionExpired(entry);
        Entry current = batches.get(batchId);
        return new Status(current.ticketStatus(), current.batchStatus());
    }

    /**
     * Consumes a successful attachment batch exactly once when the matching schedule create is committed.
     * The batch cannot be reused by another operation, lease generation, or parent resource.
     */
    public synchronized boolean consumeForScheduleCreate(String batchId,
                                                          TrustedDesktopConnection connection,
                                                          ReadyOaSessionLease lease,
                                                          String clientOperationId,
                                                          String parentResourceId) {
        return consumeForScheduleCreate(
                batchId, connection, lease, clientOperationId, parentResourceId, null);
    }

    public synchronized boolean consumeForScheduleCreate(String batchId,
                                                          TrustedDesktopConnection connection,
                                                          ReadyOaSessionLease lease,
                                                          String clientOperationId,
                                                          String parentResourceId,
                                                          String parentRecordId) {
        Entry entry = batches.get(batchId);
        return consumeForScheduleCreate(
                batchId, connection, lease, clientOperationId,
                entry == null ? null : entry.scope(),
                entry == null ? null : entry.teamId(),
                entry == null ? null : entry.scheduleTypeId(),
                parentResourceId, parentRecordId);
    }

    public synchronized boolean consumeForScheduleCreate(String batchId,
                                                          TrustedDesktopConnection connection,
                                                          ReadyOaSessionLease lease,
                                                          String clientOperationId,
                                                          String scope,
                                                          String teamId,
                                                          String scheduleTypeId,
                                                          String parentResourceId,
                                                          String parentRecordId) {
        Entry entry = batches.get(batchId);
        if (!isConsumableForScheduleCreate(
                entry, connection, lease, clientOperationId, scope, teamId, scheduleTypeId,
                parentResourceId, parentRecordId)) return false;
        batches.put(batchId, entry.with(TicketStatus.SUCCEEDED, BatchStatus.CONSUMED));
        return true;
    }

    /** Read-only CAS preflight used before a non-idempotent OA schedule write. */
    public synchronized boolean canConsumeForScheduleCreate(String batchId,
                                                             TrustedDesktopConnection connection,
                                                             ReadyOaSessionLease lease,
                                                             String clientOperationId,
                                                             String parentResourceId) {
        return canConsumeForScheduleCreate(
                batchId, connection, lease, clientOperationId, parentResourceId, null);
    }

    public synchronized boolean canConsumeForScheduleCreate(String batchId,
                                                             TrustedDesktopConnection connection,
                                                             ReadyOaSessionLease lease,
                                                             String clientOperationId,
                                                             String parentResourceId,
                                                             String parentRecordId) {
        BusinessAttachmentBatchRecord batch = repository == null ? null : repository.findBatch(batchId).orElse(null);
        Entry entry = repository == null ? batches.get(batchId) : null;
        return canConsumeForScheduleCreate(
                batchId, connection, lease, clientOperationId,
                batch == null ? entry == null ? null : entry.scope() : batch.scope(),
                batch == null ? entry == null ? null : entry.teamId() : batch.teamId(),
                batch == null ? entry == null ? null : entry.scheduleTypeId() : batch.scheduleTypeId(),
                parentResourceId, parentRecordId);
    }

    public synchronized boolean canConsumeForScheduleCreate(String batchId,
                                                             TrustedDesktopConnection connection,
                                                             ReadyOaSessionLease lease,
                                                             String clientOperationId,
                                                             String scope,
                                                             String teamId,
                                                             String scheduleTypeId,
                                                             String parentResourceId,
                                                             String parentRecordId) {
        if (repository != null) {
            BusinessAttachmentBatchRecord batch = repository.findBatch(batchId).orElse(null);
            return batch != null && batch.state() == BatchStatus.READY
                    && batch.expiresAt().isAfter(clock.instant())
                    && "SCHEDULE_CREATE".equals(batch.operation())
                    && Objects.equals(batch.clientOperationId(), clientOperationId)
                    && Objects.equals(batch.scope(), scope)
                    && Objects.equals(batch.teamId(), teamId)
                    && Objects.equals(batch.scheduleTypeId(), scheduleTypeId)
                    && Objects.equals(batch.parentResourceId(), parentResourceId)
                    && Objects.equals(batch.parentRecordId(), parentRecordId)
                    && matches(batch, connection, lease);
        }
        return isConsumableForScheduleCreate(batches.get(batchId), connection, lease,
                clientOperationId, scope, teamId, scheduleTypeId, parentResourceId, parentRecordId);
    }

    /**
     * Durable READY -> CONSUMING CAS used immediately before the non-idempotent OA schedule write.
     * The returned file ids remain server-only char arrays and must be closed on every path.
     */
    public synchronized ScheduleAttachmentConsumption beginScheduleCreate(
            String batchId,
            TrustedDesktopConnection connection,
            ReadyOaSessionLease lease,
            String clientOperationId,
            String parentResourceId) {
        BusinessAttachmentBatchRecord batch = repository == null ? null : repository.findBatch(batchId).orElse(null);
        return beginScheduleCreate(batchId, connection, lease, clientOperationId, parentResourceId,
                lease.userId(), batch == null ? "CASE" : batch.parentRelationType(),
                null, batch == null ? "0" : batch.formRevision());
    }

    public synchronized ScheduleAttachmentConsumption beginScheduleCreate(
            String batchId,
            TrustedDesktopConnection connection,
            ReadyOaSessionLease lease,
            String clientOperationId,
            String parentResourceId,
            String actorUserId,
            String parentRelationType,
            String currentFormRevision) {
        return beginScheduleCreate(batchId, connection, lease, clientOperationId, parentResourceId,
                actorUserId, parentRelationType, null, currentFormRevision);
    }

    public synchronized ScheduleAttachmentConsumption beginScheduleCreate(
            String batchId,
            TrustedDesktopConnection connection,
            ReadyOaSessionLease lease,
            String clientOperationId,
            String parentResourceId,
            String actorUserId,
            String parentRelationType,
            String parentRecordId,
            String currentFormRevision) {
        BusinessAttachmentBatchRecord batch = repository == null ? null : repository.findBatch(batchId).orElse(null);
        return beginScheduleCreate(batchId, connection, lease, clientOperationId, actorUserId,
                batch == null ? null : batch.scope(),
                batch == null ? null : batch.teamId(),
                batch == null ? null : batch.scheduleTypeId(),
                parentRelationType, parentResourceId, parentRecordId, currentFormRevision);
    }

    public synchronized ScheduleAttachmentConsumption beginScheduleCreate(
            String batchId,
            TrustedDesktopConnection connection,
            ReadyOaSessionLease lease,
            String clientOperationId,
            String actorUserId,
            String scope,
            String teamId,
            String scheduleTypeId,
            String parentRelationType,
            String parentResourceId,
            String parentRecordId,
            String currentFormRevision) {
        requireBinding(connection, lease);
        if (repository == null || fileIdStore == null) {
            throw new IllegalStateException("durable attachment storage is unavailable");
        }
        BusinessAttachmentBatchRecord batch = repository.findBatch(batchId)
                .orElseThrow(() -> new TicketUnavailableException("attachment upload batch is unavailable"));
        boolean won = repository.beginScheduleConsumption(batchId, connection.desktopInstanceId(),
                connection.desktopSessionId(), lease.authSessionId(), lease.tenantId(), lease.generation(),
                clientOperationId, actorUserId, scope, teamId, scheduleTypeId,
                parentRelationType, parentResourceId,
                parentRecordId, currentFormRevision, clock.instant());
        if (!won) throw new TicketUnavailableException("attachment upload batch is unavailable");
        try {
            BusinessAttachmentFileIdStore.StoredFileIds ids = fileIdStore.load(batch.fileIdSecretRef());
            return new ScheduleAttachmentConsumption(batchId, batch.fileIdSecretRef(), ids);
        } catch (RuntimeException failure) {
            repository.finishScheduleConsumption(batchId, BatchStatus.OUTCOME_UNKNOWN, clock.instant());
            cleanupSecret(batch.fileIdSecretRef(), "FILE_IDS", "ATTACHMENT_OUTCOME_UNKNOWN");
            throw new TicketUnavailableException("attachment upload batch is unavailable");
        }
    }

    public synchronized void finishScheduleCreate(ScheduleAttachmentConsumption consumption,
                                                  BatchStatus terminalState) {
        if (consumption == null) return;
        if (terminalState != BatchStatus.CONSUMED && terminalState != BatchStatus.FAILED
                && terminalState != BatchStatus.OUTCOME_UNKNOWN) {
            throw new IllegalArgumentException("invalid attachment terminal state");
        }
        try {
            if (!repository.finishScheduleConsumption(consumption.batchId(), terminalState, clock.instant())) {
                throw new IllegalStateException("attachment batch state changed concurrently");
            }
        } finally {
            consumption.close();
            cleanupSecret(consumption.secretRef(), "FILE_IDS", "ATTACHMENT_" + terminalState.name());
        }
    }

    private boolean isConsumableForScheduleCreate(Entry entry,
                                                   TrustedDesktopConnection connection,
                                                   ReadyOaSessionLease lease,
                                                   String clientOperationId,
                                                   String scope,
                                                   String teamId,
                                                   String scheduleTypeId,
                                                   String parentResourceId,
                                                   String parentRecordId) {
        return entry != null && entry.ticketStatus() == TicketStatus.SUCCEEDED
                && entry.batchStatus() == BatchStatus.READY && !isExpired(entry)
                && "SCHEDULE_CREATE".equals(entry.operation())
                && Objects.equals(entry.clientOperationId(), clientOperationId)
                && Objects.equals(entry.scope(), scope)
                && Objects.equals(entry.teamId(), teamId)
                && Objects.equals(entry.scheduleTypeId(), scheduleTypeId)
                && Objects.equals(entry.parentResourceId(), parentResourceId)
                && Objects.equals(entry.parentRecordId(), parentRecordId)
                && matches(entry.connection(), connection) && matches(entry.lease(), lease);
    }

    public synchronized int revokeForConnection(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        requireBinding(connection, lease);
        if (repository != null) {
            List<String> refs = repository.fileIdSecretRefsForConnection(connection.desktopInstanceId(),
                    connection.desktopSessionId(), lease.authSessionId(), lease.generation());
            List<String> declarationRefs = repository.declarationSecretRefsForConnection(
                    connection.desktopInstanceId(), connection.desktopSessionId(),
                    lease.authSessionId(), lease.generation());
            int count = repository.revoke(connection.desktopInstanceId(), connection.desktopSessionId(),
                    lease.authSessionId(), lease.generation(), clock.instant());
            refs.forEach(ref -> cleanupSecret(ref, "FILE_IDS", "ATTACHMENT_REVOKED"));
            declarationRefs.forEach(ref -> cleanupSecret(ref, "DECLARATION", "ATTACHMENT_REVOKED"));
            return count;
        }
        int count = 0;
        for (Map.Entry<String, Entry> item : List.copyOf(batches.entrySet())) {
            Entry value = item.getValue();
            if (!matches(value.connection(), connection) || !matches(value.lease(), lease)) continue;
            if (value.ticketStatus() == TicketStatus.CLAIMED
                    || value.ticketStatus() == TicketStatus.IN_FLIGHT) {
                batches.put(item.getKey(),
                        value.with(TicketStatus.OUTCOME_UNKNOWN, BatchStatus.OUTCOME_UNKNOWN));
                count++;
            } else if (value.batchStatus() == BatchStatus.CONSUMING) {
                batches.put(item.getKey(), value.with(value.ticketStatus(), BatchStatus.OUTCOME_UNKNOWN));
            } else if (value.batchStatus() == BatchStatus.PENDING
                    || value.batchStatus() == BatchStatus.READY) {
                TicketStatus ticket = value.ticketStatus() == TicketStatus.ISSUED
                        ? TicketStatus.REVOKED : value.ticketStatus();
                batches.put(item.getKey(), value.with(ticket, BatchStatus.REVOKED));
                if (value.ticketStatus() == TicketStatus.ISSUED) count++;
            }
        }
        return count;
    }

    public synchronized int purgeExpired() {
        if (repository != null) {
            List<String> refs = repository.recoverableFileIdSecretRefs(clock.instant());
            List<String> declarationRefs = repository.recoverableDeclarationSecretRefs(clock.instant());
            BusinessAttachmentRepository.RecoveryCounts counts = repository.recover(clock.instant());
            refs.forEach(ref -> cleanupSecret(ref, "FILE_IDS", "ATTACHMENT_RECOVERY"));
            declarationRefs.forEach(ref -> cleanupSecret(ref, "DECLARATION", "ATTACHMENT_RECOVERY"));
            if (secretCleanup != null) secretCleanup.drainPending();
            return counts.expiredTickets() + counts.unknownTickets();
        }
        int count = 0;
        for (Entry entry : List.copyOf(batches.values())) {
            if (isExpired(entry)) {
                transitionExpired(entry);
                count++;
            }
        }
        return count;
    }

    public static String sanitizeFileName(String fileName) {
        requireText(fileName, "fileName");
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String basename = slash < 0 ? normalized : normalized.substring(slash + 1);
        basename = basename.strip();
        if (basename.isBlank() || basename.equals(".") || basename.equals("..") || basename.indexOf('\0') >= 0
                || basename.length() > 255) throw new IllegalArgumentException("invalid file name");
        return basename;
    }

    private static String diagnosticMediaType(String mediaType) {
        if (mediaType == null) {
            return "[REDACTED]";
        }
        String normalized = mediaType.strip().toLowerCase(Locale.ROOT);
        return EXTENSION_MEDIA_TYPES.containsValue(normalized) ? normalized : "[REDACTED]";
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte current : digest) value.append(String.format(Locale.ROOT, "%02x", current));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<FileDeclaration> normalizeDeclarations(List<FileDeclaration> declarations) {
        if (declarations == null || declarations.isEmpty() || declarations.size() > MAX_FILE_COUNT) {
            throw new IllegalArgumentException("invalid attachment file count");
        }
        List<FileDeclaration> normalized = new ArrayList<>(declarations.size());
        Set<String> names = new HashSet<>();
        long total = 0;
        for (FileDeclaration declaration : declarations) {
            if (declaration == null) throw new IllegalArgumentException("invalid attachment declaration");
            FileDeclaration file = declaration.normalized();
            if (!names.add(file.fileName().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate attachment file name");
            if (file.sizeBytes() >= MAX_SINGLE_BYTES) throw new IllegalArgumentException("attachment file is too large");
            total = Math.addExact(total, file.sizeBytes());
            if (total >= MAX_TOTAL_BYTES) throw new IllegalArgumentException("attachments are too large");
            normalized.add(file);
        }
        return List.copyOf(normalized);
    }

    private static void validateUploaded(List<FileDeclaration> declarations, List<UploadedFile> uploadedFiles) {
        if (uploadedFiles == null || uploadedFiles.size() != declarations.size()) throw new IllegalArgumentException();
        Map<String, UploadedFile> actual = new HashMap<>();
        for (UploadedFile uploaded : uploadedFiles) {
            if (uploaded == null) throw new IllegalArgumentException();
            UploadedFile normalized = uploaded.normalized();
            if (actual.put(normalized.fileName().toLowerCase(Locale.ROOT), normalized) != null) throw new IllegalArgumentException();
            if (normalized.sizeBytes() >= MAX_SINGLE_BYTES) throw new IllegalArgumentException();
        }
        long total = 0;
        for (FileDeclaration declaration : declarations) {
            UploadedFile actualFile = actual.get(declaration.fileName().toLowerCase(Locale.ROOT));
            if (actualFile == null || actualFile.sizeBytes() != declaration.sizeBytes()
                    || !actualFile.mediaType().equals(declaration.mediaType())
                    || (declaration.sha256() != null && !declaration.sha256().equalsIgnoreCase(actualFile.sha256()))) {
                throw new IllegalArgumentException();
            }
            total = Math.addExact(total, actualFile.sizeBytes());
        }
        if (total >= MAX_TOTAL_BYTES) throw new IllegalArgumentException();
    }

    private boolean isExpired(Entry entry) { return !entry.expiresAt().isAfter(clock.instant()); }

    private void transitionClaim(UploadClaim claim, TicketStatus ticket, BatchStatus batch) {
        if (claim == null) return;
        if (repository != null) {
            transitionDurable(claim, ticket, batch);
            return;
        }
        Entry entry = batches.get(claim.batchId());
        if (entry == null || entry.ticketStatus() != TicketStatus.IN_FLIGHT
                || !constantTimeEquals(entry.ticket(), claim.ticket())
                || !matches(entry.connection(), claim.connection()) || !matches(entry.lease(), claim.lease())) return;
        transition(entry, ticket, batch);
    }

    private void transition(Entry entry, TicketStatus ticket, BatchStatus batch) {
        if (repository != null) {
            repository.transitionUpload(entry.batchId(), ticketDigest(entry.ticket()),
                    entry.ticketStatus(), ticket, entry.batchStatus(), batch, clock.instant());
        }
        batches.put(entry.batchId(), entry.with(ticket, batch));
    }

    private void transitionExpired(Entry entry) {
        if (entry.ticketStatus() == TicketStatus.ISSUED || entry.ticketStatus() == TicketStatus.IN_FLIGHT) {
            transition(entry, TicketStatus.EXPIRED, BatchStatus.REVOKED);
        }
    }
    private static void requireBinding(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(lease, "lease");
        if (!matches(connection, lease)) throw new IllegalArgumentException("attachment binding does not match the active desktop lease");
    }
    private static boolean matches(TrustedDesktopConnection connection, ReadyOaSessionLease lease) {
        return connection != null && lease != null && connection.desktopInstanceId().equals(lease.desktopInstanceId())
                && connection.desktopSessionId().equals(lease.desktopSessionId())
                && connection.webSocketSessionId().equals(lease.webSocketSessionId());
    }
    private static boolean matches(TrustedDesktopConnection left, TrustedDesktopConnection right) {
        return left != null && right != null && left.reservationId().equals(right.reservationId())
                && left.desktopInstanceId().equals(right.desktopInstanceId()) && left.desktopSessionId().equals(right.desktopSessionId())
                && left.webSocketSessionId().equals(right.webSocketSessionId());
    }
    private static boolean matches(ReadyOaSessionLease left, ReadyOaSessionLease right) {
        return left != null && right != null && left.authSessionId().equals(right.authSessionId())
                && left.desktopInstanceId().equals(right.desktopInstanceId()) && left.desktopSessionId().equals(right.desktopSessionId())
                && left.webSocketSessionId().equals(right.webSocketSessionId()) && left.tenantId().equals(right.tenantId())
                && left.generation() == right.generation() && left.activeCredentialRef().equals(right.activeCredentialRef());
    }
    private static boolean matches(BusinessAttachmentBatchRecord batch,
                                   TrustedDesktopConnection connection,
                                   ReadyOaSessionLease lease) {
        return batch != null && connection != null && lease != null
                && batch.desktopInstanceId().equals(connection.desktopInstanceId())
                && batch.desktopSessionId().equals(connection.desktopSessionId())
                && batch.authSessionId().equals(lease.authSessionId())
                && batch.tenantId().equals(lease.tenantId())
                && batch.identityGeneration() == lease.generation()
                && matches(connection, lease);
    }
    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
    private static String ticketDigest(String ticket) {
        return sha256Hex(ticket == null ? new byte[0] : ticket.getBytes(StandardCharsets.US_ASCII));
    }
    private static String opaqueId() { return UUID.randomUUID().toString().replace("-", "") + Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII)); }
    private static void requireText(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank"); }

    private UploadReceipt completeDurable(UploadClaim claim, List<UploadedFile> uploadedFiles,
                                          BusinessAttachmentRemoteUploader.UploadedRemoteFiles remoteFiles) {
        validateBeforeRemote(claim, uploadedFiles);
        if (remoteFiles == null || remoteFiles.fileCount() != uploadedFiles.size()) {
            transitionDurable(claim, TicketStatus.OUTCOME_UNKNOWN, BatchStatus.OUTCOME_UNKNOWN);
            throw new TicketRejectedException("attachment upload outcome is unknown");
        }
        String secretRef = null;
        try {
            List<char[]> ids = remoteFiles.copyFileIds();
            try {
                secretRef = fileIdStore.save(claim.batchId(), ids);
            } finally {
                ids.forEach(value -> java.util.Arrays.fill(value, '\0'));
            }
            if (!repository.completeUpload(claim.batchId(), ticketDigest(claim.ticket()),
                    secretRef, clock.instant())) {
                throw new TicketRejectedException("attachment upload outcome is unknown");
            }
            BusinessAttachmentBatchRecord completed = repository.findBatch(claim.batchId()).orElse(null);
            if (completed != null) {
                cleanupSecret(completed.declarationSecretRef(), "DECLARATION", "ATTACHMENT_UPLOAD_COMPLETED");
            }
            return new UploadReceipt(claim.batchId(), uploadedFiles.size());
        } catch (RuntimeException failure) {
            if (secretRef != null) {
                cleanupSecret(secretRef, "FILE_IDS", "ATTACHMENT_OUTCOME_UNKNOWN");
            }
            transitionDurable(claim, TicketStatus.OUTCOME_UNKNOWN, BatchStatus.OUTCOME_UNKNOWN);
            throw failure;
        }
    }

    private void transitionDurable(UploadClaim claim, TicketStatus ticket, BatchStatus batch) {
        BusinessAttachmentBatchRecord current = repository.findBatch(claim.batchId()).orElse(null);
        BusinessAttachmentTicketRecord currentTicket = repository.findTicketByBatchId(claim.batchId()).orElse(null);
        if (current == null || currentTicket == null || currentTicket.state() != TicketStatus.IN_FLIGHT
                || current.state() != BatchStatus.PENDING
                || !constantTimeEquals(currentTicket.ticketDigest(), ticketDigest(claim.ticket()))
                || !matches(current, claim.connection(), claim.lease())) return;
        repository.transitionUpload(claim.batchId(), ticketDigest(claim.ticket()),
                TicketStatus.IN_FLIGHT, ticket, BatchStatus.PENDING, batch, clock.instant());
        cleanupSecret(current.declarationSecretRef(), "DECLARATION", "ATTACHMENT_" + batch.name());
        if (current.fileIdSecretRef() != null) {
            cleanupSecret(current.fileIdSecretRef(), "FILE_IDS", "ATTACHMENT_" + batch.name());
        }
    }

    private void cleanupSecret(String secretRef, String kind, String reasonCode) {
        if (secretRef == null || secretRef.isBlank()) return;
        if (secretCleanup != null) {
            secretCleanup.scheduleAndAttempt(secretRef, kind, reasonCode);
            return;
        }
        try { fileIdStore.delete(secretRef); } catch (RuntimeException ignored) { }
    }

    private static String writeDeclarations(List<FileDeclaration> declarations) {
        try { return JSON.writeValueAsString(declarations); }
        catch (Exception failure) { throw new IllegalStateException("attachment declarations cannot be stored", failure); }
    }

    private static List<FileDeclaration> readDeclarations(String json) {
        try {
            return normalizeDeclarations(JSON.readValue(json,
                    new TypeReference<List<FileDeclaration>>() { }));
        } catch (Exception failure) {
            throw new TicketUnavailableException("attachment upload declaration is unavailable");
        }
    }

    private record Entry(String batchId, String ticket, TrustedDesktopConnection connection, ReadyOaSessionLease lease,
                         String operation, String clientOperationId, String actorUserId,
                         String scope, String teamId, String scheduleTypeId, String parentRelationType,
                         String parentResourceId,
                         String parentRecordId, List<FileDeclaration> declarations,
                         Instant expiresAt, TicketStatus ticketStatus, BatchStatus batchStatus) {
        private Entry with(TicketStatus ticket, BatchStatus batch) {
            return new Entry(batchId, this.ticket, connection, lease, operation, clientOperationId,
                    actorUserId, scope, teamId, scheduleTypeId, parentRelationType,
                    parentResourceId, parentRecordId, declarations, expiresAt, ticket, batch);
        }
    }

    public record PreparedBatch(String batchId, String ticket, Instant expiresAt) {
        @Override public String toString() { return "PreparedBatch(batchId=[REDACTED], ticket=[REDACTED], expiresAt=" + expiresAt + ")"; }
    }
    public record UploadClaim(String batchId, String ticket, TrustedDesktopConnection connection,
                              ReadyOaSessionLease lease, List<FileDeclaration> declarations) {
        public UploadClaim {
            declarations = List.copyOf(declarations == null ? List.of() : declarations);
        }
        @Override public String toString() { return "UploadClaim(batchId=[REDACTED], ticket=[REDACTED])"; }
    }
    public record UploadReceipt(String batchId, int fileCount) {
        @Override public String toString() { return "UploadReceipt(batchId=[REDACTED], fileCount=" + fileCount + ")"; }
    }
    public record Status(TicketStatus ticket, BatchStatus batch) { }
    public static final class ScheduleAttachmentConsumption implements AutoCloseable {
        private final String batchId;
        private final String secretRef;
        private final BusinessAttachmentFileIdStore.StoredFileIds ids;

        private ScheduleAttachmentConsumption(String batchId, String secretRef,
                                              BusinessAttachmentFileIdStore.StoredFileIds ids) {
            this.batchId = batchId;
            this.secretRef = secretRef;
            this.ids = ids;
        }

        String batchId() { return batchId; }
        String secretRef() { return secretRef; }
        public List<char[]> fileIds() { return ids.values(); }
        @Override public void close() { ids.close(); }
        @Override public String toString() {
            return "ScheduleAttachmentConsumption(batchId=[REDACTED], fileIds=[REDACTED])";
        }
    }
    public record FileDeclaration(String fileName, long sizeBytes, String mediaType, String sha256) {
        private FileDeclaration normalized() {
            String safeName = sanitizeFileName(fileName);
            if (sizeBytes <= 0) throw new IllegalArgumentException("invalid attachment size");
            String safeMediaType = mediaType == null ? "" : mediaType.strip().toLowerCase(Locale.ROOT);
            String extension = safeName.substring(safeName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (!EXTENSION_MEDIA_TYPES.containsKey(extension) || !EXTENSION_MEDIA_TYPES.get(extension).equals(safeMediaType)) {
                throw new IllegalArgumentException("attachment extension and media type do not match");
            }
            String safeHash = sha256 == null || sha256.isBlank() ? null : sha256.strip().toLowerCase(Locale.ROOT);
            if (safeHash != null && !SHA256.matcher(safeHash).matches()) throw new IllegalArgumentException("invalid attachment hash");
            return new FileDeclaration(safeName, sizeBytes, safeMediaType, safeHash);
        }

        @Override
        public String toString() {
            return "FileDeclaration[fileName=[REDACTED], sizeBytes=" + sizeBytes
                    + ", mediaType=" + diagnosticMediaType(mediaType) + ", sha256=[REDACTED]]";
        }
    }
    public record UploadedFile(String fileName, long sizeBytes, String mediaType, String sha256) {
        private UploadedFile normalized() {
            FileDeclaration declaration = new FileDeclaration(fileName, sizeBytes, mediaType, sha256).normalized();
            return new UploadedFile(declaration.fileName(), declaration.sizeBytes(), declaration.mediaType(), declaration.sha256());
        }

        @Override
        public String toString() {
            return "UploadedFile[fileName=[REDACTED], sizeBytes=" + sizeBytes
                    + ", mediaType=" + diagnosticMediaType(mediaType) + ", sha256=[REDACTED]]";
        }
    }
    public enum TicketStatus { ISSUED, CLAIMED, IN_FLIGHT, SUCCEEDED, REJECTED, OUTCOME_UNKNOWN, EXPIRED, REVOKED }
    public enum BatchStatus { PENDING, READY, CONSUMING, CONSUMED, FAILED, OUTCOME_UNKNOWN, REVOKED }
    public static class TicketUnavailableException extends IllegalStateException { public TicketUnavailableException(String message) { super(message); } }
    public static class TicketRejectedException extends IllegalStateException { public TicketRejectedException(String message) { super(message); } }
}
