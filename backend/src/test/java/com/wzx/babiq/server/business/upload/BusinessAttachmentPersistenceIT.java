package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "babiq.business.enabled=false")
class BusinessAttachmentPersistenceIT {
    private static final Path DATABASE = Path.of("target", "test-db",
            "business-attachments-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", DATABASE::toString);
    }

    @Autowired private BusinessAttachmentRepository repository;
    @Autowired private BusinessAttachmentSecretCleanupRepository cleanupRepository;
    @Autowired private DataSource dataSource;
    @Autowired private SecretStore secretStore;

    @Test
    void ticket_and_batch_claim_use_durable_compare_and_swap() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        repository.create(
                new BusinessAttachmentBatchRecord(
                        "batch-1", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                        "SCHEDULE_CREATE", "operation-1", "case-1", "form-1",
                        BusinessAttachmentTicketService.BatchStatus.PENDING, null,
                        now.plusSeconds(60), now, now),
                new BusinessAttachmentTicketRecord(
                        "ticket-digest-1", "batch-1", "instance-1", "desktop-1", "auth-1",
                        "tenant-1", 7, BusinessAttachmentTicketService.TicketStatus.ISSUED,
                        now.plusSeconds(60), null, null, now));

        assertThat(repository.claimUpload(
                "batch-1", "ticket-digest-1", "instance-1", "desktop-1", "auth-1",
                "tenant-1", 7, now)).isTrue();
        assertThat(repository.claimUpload(
                "batch-1", "ticket-digest-1", "instance-1", "desktop-1", "auth-1",
                "tenant-1", 7, now)).isFalse();
        assertThat(repository.findTicketByBatchId("batch-1").orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT);
        assertThat(repository.findBatch("batch-1").orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.BatchStatus.PENDING);
    }

    @Test
    void only_one_schedule_consumer_wins_and_sqlite_never_contains_remote_file_ids() throws Exception {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        String remoteFileIdCanary = "oa-file-id-canary-93ad";
        repository.create(
                new BusinessAttachmentBatchRecord(
                        "batch-2", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                        "SCHEDULE_CREATE", "operation-2", "case-2", "form-2",
                        BusinessAttachmentTicketService.BatchStatus.READY,
                        "keystore://business.attachment.fileIds.safe-reference",
                        now.plusSeconds(60), now, now),
                new BusinessAttachmentTicketRecord(
                        "ticket-digest-2", "batch-2", "instance-1", "desktop-1", "auth-1",
                        "tenant-1", 7, BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                        now.plusSeconds(60), now, now, now));

        assertThat(repository.beginScheduleConsumption(
                "batch-2", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                "operation-2", "case-2", "form-2", now)).isTrue();
        assertThat(repository.beginScheduleConsumption(
                "batch-2", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                "operation-2", "case-2", "form-2", now)).isFalse();

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT quote(batch_id) || quote(COALESCE(file_id_secret_ref, ''))
                     FROM bq_business_attachment_batches
                     """)) {
            StringBuilder persisted = new StringBuilder();
            while (rows.next()) persisted.append(rows.getString(1));
            assertThat(persisted).doesNotContain(remoteFileIdCanary);
        }
    }

    @Test
    void schedule_consumption_cas_matches_scope_team_and_schedule_type_independently() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        repository.create(
                new BusinessAttachmentBatchRecord(
                        "batch-binding-cas", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                        "SCHEDULE_CREATE", "operation-binding", "user-1",
                        "TEAM", "team-1", "type-1",
                        "CASE", "case-binding", null, "form-binding", "declaration-ref",
                        BusinessAttachmentTicketService.BatchStatus.READY,
                        "keystore://business.attachment.fileIds.binding-reference",
                        now.plusSeconds(60), now, now),
                new BusinessAttachmentTicketRecord(
                        "ticket-binding-cas", "batch-binding-cas",
                        "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                        BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                        now.plusSeconds(60), now, now, now));

        assertThat(repository.beginScheduleConsumption(
                "batch-binding-cas", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                "operation-binding", "user-1", "PERSONAL", null, "type-1",
                "CASE", "case-binding", null, "form-binding", now)).isFalse();
        assertThat(repository.findBatch("batch-binding-cas").orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.BatchStatus.READY);
        assertThat(repository.beginScheduleConsumption(
                "batch-binding-cas", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                "operation-binding", "user-1", "TEAM", "team-2", "type-1",
                "CASE", "case-binding", null, "form-binding", now)).isFalse();
        assertThat(repository.beginScheduleConsumption(
                "batch-binding-cas", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                "operation-binding", "user-1", "TEAM", "team-1", "type-2",
                "CASE", "case-binding", null, "form-binding", now)).isFalse();
        assertThat(repository.beginScheduleConsumption(
                "batch-binding-cas", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                "operation-binding", "user-1", "TEAM", "team-1", "type-1",
                "CASE", "case-binding", null, "form-binding", now)).isTrue();
    }

    @Test
    void restart_recovery_terminalizes_in_flight_and_consuming_without_replay() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        repository.recover(now);
        repository.create(
                new BusinessAttachmentBatchRecord(
                        "batch-3", "instance-1", "desktop-1", "auth-1", "tenant-1", 7,
                        "SCHEDULE_CREATE", "operation-3", "case-3", "form-3",
                        BusinessAttachmentTicketService.BatchStatus.PENDING,
                        "keystore://business.attachment.fileIds.recovery",
                        now.plusSeconds(60), now, now),
                new BusinessAttachmentTicketRecord(
                        "ticket-digest-3", "batch-3", "instance-1", "desktop-1", "auth-1",
                        "tenant-1", 7, BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT,
                        now.plusSeconds(60), now, null, now));

        BusinessAttachmentRepository.RecoveryCounts counts = repository.recover(now);

        assertThat(counts.unknownTickets()).isEqualTo(1);
        assertThat(counts.unknownBatches()).isEqualTo(1);
        assertThat(repository.findTicketByBatchId("batch-3").orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN);
        assertThat(repository.findBatch("batch-3").orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
    }

    @Test
    void lease_revocation_preserves_unknown_outcomes_for_in_flight_work() {
        Instant now = Instant.parse("2026-07-29T01:00:00Z");
        create("revoke-issued", BusinessAttachmentTicketService.TicketStatus.ISSUED,
                BusinessAttachmentTicketService.BatchStatus.PENDING, now);
        create("revoke-uploading", BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT,
                BusinessAttachmentTicketService.BatchStatus.PENDING, now);
        create("revoke-ready", BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.READY, now);
        create("revoke-consuming", BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.CONSUMING, now);
        create("replacement-issued", "auth-replacement",
                BusinessAttachmentTicketService.TicketStatus.ISSUED,
                BusinessAttachmentTicketService.BatchStatus.PENDING, now);
        create("replacement-uploading", "auth-replacement",
                BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT,
                BusinessAttachmentTicketService.BatchStatus.PENDING, now);
        create("replacement-ready", "auth-replacement",
                BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.READY, now);
        create("replacement-consuming", "auth-replacement",
                BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.CONSUMING, now);

        assertThat(repository.fileIdSecretRefsForConnection(
                "instance-revoke", "desktop-revoke", "auth-revoke", 11))
                .containsExactlyInAnyOrder(
                        "keystore://fileIds-revoke-ready",
                        "keystore://fileIds-revoke-consuming");
        assertThat(repository.declarationSecretRefsForConnection(
                "instance-revoke", "desktop-revoke", "auth-revoke", 11))
                .containsExactlyInAnyOrder(
                        "keystore://declaration-revoke-issued",
                        "keystore://declaration-revoke-uploading",
                        "keystore://declaration-revoke-ready",
                        "keystore://declaration-revoke-consuming");
        repository.revoke(
                "instance-revoke", "desktop-revoke", "auth-revoke", 11, now.plusSeconds(1));

        assertState("revoke-issued", BusinessAttachmentTicketService.TicketStatus.REVOKED,
                BusinessAttachmentTicketService.BatchStatus.REVOKED);
        assertState("revoke-uploading", BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN,
                BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertState("revoke-ready", BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.REVOKED);
        assertState("revoke-consuming", BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertState("replacement-issued", BusinessAttachmentTicketService.TicketStatus.ISSUED,
                BusinessAttachmentTicketService.BatchStatus.PENDING);
        assertState("replacement-uploading", BusinessAttachmentTicketService.TicketStatus.IN_FLIGHT,
                BusinessAttachmentTicketService.BatchStatus.PENDING);
        assertState("replacement-ready", BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.READY);
        assertState("replacement-consuming", BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.CONSUMING);
    }

    @Test
    void remote_file_ids_live_only_in_secret_store_and_schedule_consumes_them_once() throws Exception {
        BusinessAttachmentFileIdStore attachmentSecrets = new BusinessAttachmentFileIdStore(secretStore);
        BusinessAttachmentSecretCleanupService cleanup =
                new BusinessAttachmentSecretCleanupService(cleanupRepository, attachmentSecrets, java.time.Clock.systemUTC());
        BusinessAttachmentTicketService service = new BusinessAttachmentTicketService(
                repository, attachmentSecrets, cleanup);
        TrustedDesktopConnection connection =
                new TrustedDesktopConnection("reservation-secure", "instance-secure", "desktop-secure", "ws-secure");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth-secure", "instance-secure", "desktop-secure", "ws-secure",
                "user-secure", "tenant-secure", "2", 9, "credential-secure",
                1, Instant.now());
        byte[] contents = {1, 2, 3};
        String hash = BusinessAttachmentTicketService.sha256Hex(contents);
        var prepared = service.prepare(connection, lease, "SCHEDULE_CREATE",
                "operation-secure", "case-secure",
                List.of(new BusinessAttachmentTicketService.FileDeclaration(
                        "a.pdf", contents.length, "application/pdf", hash)));
        // Simulate a service recreation: SQLite + SecretStore, not the old process-local Entry map,
        // must remain sufficient to claim and validate the issued ticket.
        BusinessAttachmentTicketService restartedService = new BusinessAttachmentTicketService(
                repository, attachmentSecrets, cleanup);
        var claim = restartedService.claim(prepared.batchId(), prepared.ticket(), connection, lease);
        String fileIdCanary = "oa-file-id-canary-secret-store-only";
        try (var remote = new BusinessAttachmentRemoteUploader.UploadedRemoteFiles(
                List.of(fileIdCanary.toCharArray()))) {
            restartedService.complete(claim, List.of(new BusinessAttachmentTicketService.UploadedFile(
                    "a.pdf", contents.length, "application/pdf", hash)), remote);
        }

        try (var consumption = restartedService.beginScheduleCreate(
                prepared.batchId(), connection, lease, "operation-secure", "case-secure")) {
            assertThat(new String(consumption.fileIds().getFirst())).isEqualTo(fileIdCanary);
            restartedService.finishScheduleCreate(consumption, BusinessAttachmentTicketService.BatchStatus.CONSUMED);
        }
        assertThatThrownBy(() -> restartedService.beginScheduleCreate(
                prepared.batchId(), connection, lease, "operation-secure", "case-secure"))
                .isInstanceOf(BusinessAttachmentTicketService.TicketUnavailableException.class);
        assertThat(cleanupRepository.listPending(10)).isEmpty();

        try (var connectionToDb = dataSource.getConnection();
             var statement = connectionToDb.createStatement()) {
            for (String table : List.of("bq_business_attachment_batches", "bq_business_attachment_tickets")) {
                try (var rows = statement.executeQuery("SELECT * FROM " + table)) {
                    while (rows.next()) {
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            String persisted = rows.getString(column);
                            if (persisted != null) assertThat(persisted)
                                    .doesNotContain(fileIdCanary, "a.pdf", hash);
                        }
                    }
                }
            }
        }
    }

    @Test
    void resource_bytes_survive_registry_recreation_without_memory_storage_refs() {
        Instant now = Instant.parse("2026-07-29T02:00:00Z");
        BusinessResourceBlobStore blobs =
                new BusinessResourceBlobStore(DATABASE.resolveSibling("resource-blobs"));
        BusinessResourceHandleRegistry first = new BusinessResourceHandleRegistry(
                repository, blobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        TrustedDesktopConnection connection =
                new TrustedDesktopConnection("reservation-resource", "instance-resource", "desktop-resource", "ws-resource");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth-resource", "instance-resource", "desktop-resource", "ws-resource",
                "user-resource", "tenant-resource", "2", 12, "credential-resource",
                1, now);

        var descriptor = first.register(
                connection, lease, "image/png", new byte[]{7, 8, 9}, Duration.ofMinutes(1));
        BusinessResourceHandleRecord durable = repository.findResource(descriptor.handle()).orElseThrow();

        assertThat(durable.storageRef()).doesNotStartWith("memory://");
        BusinessResourceHandleRegistry restarted = new BusinessResourceHandleRegistry(
                repository, blobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        assertThat(restarted.resolve(descriptor.handle(), connection, lease).orElseThrow().bytes())
                .containsExactly(7, 8, 9);

        restarted.revoke(connection, lease);
        assertThat(restarted.resolve(descriptor.handle(), connection, lease)).isEmpty();
        assertThatThrownBy(() -> blobs.load(durable.storageRef(), durable.contentLength()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resource_revoke_is_exact_to_auth_session_when_generation_is_reused() {
        Instant now = Instant.parse("2026-07-29T02:30:00Z");
        BusinessResourceBlobStore blobs =
                new BusinessResourceBlobStore(DATABASE.resolveSibling(
                        "resource-auth-scope-" + UUID.randomUUID()));
        BusinessResourceHandleRegistry registry = new BusinessResourceHandleRegistry(
                repository, blobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-auth-scope", "instance-auth-scope",
                "desktop-auth-scope", "ws-auth-scope");
        ReadyOaSessionLease oldLease = new ReadyOaSessionLease(
                "auth-old", "instance-auth-scope", "desktop-auth-scope", "ws-auth-scope",
                "user-old", "tenant-auth-scope", "2", 1, "credential-old", 1, now);
        ReadyOaSessionLease replacementLease = new ReadyOaSessionLease(
                "auth-replacement", "instance-auth-scope", "desktop-auth-scope", "ws-auth-scope",
                "user-replacement", "tenant-auth-scope", "2", 1,
                "credential-replacement", 1, now);
        var oldResource = registry.register(
                connection, oldLease, "image/png", new byte[]{1}, Duration.ofMinutes(1));
        var replacementResource = registry.register(
                connection, replacementLease, "image/png", new byte[]{2}, Duration.ofMinutes(1));

        assertThat(registry.revoke(connection, oldLease)).isEqualTo(1);

        assertThat(registry.resolve(oldResource.handle(), connection, oldLease)).isEmpty();
        assertThat(registry.resolve(
                replacementResource.handle(), connection, replacementLease).orElseThrow().bytes())
                .containsExactly(2);
        assertThat(repository.findResource(replacementResource.handle())).get()
                .extracting(BusinessResourceHandleRecord::revokedAt).isNull();
    }

    @Test
    void startup_retries_failed_resource_delete_and_removes_unindexed_orphan() throws Exception {
        Instant now = Instant.parse("2026-07-29T03:00:00Z");
        Path blobRoot = DATABASE.resolveSibling("resource-recovery-" + UUID.randomUUID());
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        BusinessResourceBlobStore failingBlobs = new BusinessResourceBlobStore(
                blobRoot,
                path -> {
                    if (failFirstDelete.getAndSet(false)) {
                        throw new IOException("simulated sharing violation");
                    }
                    return Files.deleteIfExists(path);
                });
        BusinessResourceHandleRegistry first = new BusinessResourceHandleRegistry(
                repository, failingBlobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        TrustedDesktopConnection connection =
                new TrustedDesktopConnection("reservation-recovery", "instance-recovery",
                        "desktop-recovery", "ws-recovery");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth-recovery", "instance-recovery", "desktop-recovery", "ws-recovery",
                "user-recovery", "tenant-recovery", "2", 13, "credential-recovery",
                1, now);

        var descriptor = first.register(
                connection, lease, "application/pdf", new byte[]{1, 3, 5}, Duration.ofMinutes(1));
        BusinessResourceHandleRecord pending =
                repository.findResource(descriptor.handle()).orElseThrow();
        Path pendingBlob = blobRoot.resolve(pending.storageRef());

        assertThat(first.revoke(connection, lease)).isEqualTo(1);
        assertThat(Files.exists(pendingBlob)).isTrue();
        assertThat(repository.findResource(descriptor.handle())).get()
                .extracting(BusinessResourceHandleRecord::revokedAt).isNotNull();

        BusinessResourceBlobStore restartedBlobs = new BusinessResourceBlobStore(blobRoot);
        String orphanStorageRef = restartedBlobs.store(new byte[]{2, 4, 6});
        Path orphanBlob = blobRoot.resolve(orphanStorageRef);
        BusinessResourceHandleRegistry restarted = new BusinessResourceHandleRegistry(
                repository, restartedBlobs, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
                Duration.ofMinutes(5));
        BusinessAttachmentFileIdStore attachmentSecrets = new BusinessAttachmentFileIdStore(secretStore);
        BusinessAttachmentSecretCleanupService cleanup =
                new BusinessAttachmentSecretCleanupService(
                        cleanupRepository, attachmentSecrets, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC));
        BusinessAttachmentRecoveryService startupRecovery = new BusinessAttachmentRecoveryService(
                new BusinessAttachmentTicketService(repository, attachmentSecrets, cleanup),
                restarted,
                DATABASE.resolveSibling("runtime-recovery").toString());

        startupRecovery.recover();

        assertThat(repository.findResource(descriptor.handle())).isEmpty();
        assertThat(Files.exists(pendingBlob)).isFalse();
        assertThat(Files.exists(orphanBlob)).isFalse();
    }

    @Test
    void revoke_cannot_return_before_an_in_progress_resource_resolve_finishes() throws Exception {
        Instant now = Instant.parse("2026-07-29T04:00:00Z");
        Path blobRoot = DATABASE.resolveSibling("resource-resolve-race-" + UUID.randomUUID());
        BusinessResourceBlobStore realBlobs = new BusinessResourceBlobStore(blobRoot);
        BusinessResourceBlobStore blockingBlobs = mock(BusinessResourceBlobStore.class);
        CountDownLatch bytesLoaded = new CountDownLatch(1);
        CountDownLatch allowResolveReturn = new CountDownLatch(1);
        CountDownLatch revokeReturned = new CountDownLatch(1);
        when(blockingBlobs.store(any(byte[].class))).thenAnswer(invocation ->
                realBlobs.store(invocation.getArgument(0)));
        when(blockingBlobs.load(anyString(), anyLong())).thenAnswer(invocation -> {
            byte[] loaded = realBlobs.load(invocation.getArgument(0), invocation.getArgument(1));
            bytesLoaded.countDown();
            assertThat(allowResolveReturn.await(5, TimeUnit.SECONDS)).isTrue();
            return loaded;
        });
        when(blockingBlobs.delete(anyString())).thenAnswer(invocation ->
                realBlobs.delete(invocation.getArgument(0)));
        when(blockingBlobs.storageRefs()).thenAnswer(ignored -> realBlobs.storageRefs());
        BusinessResourceHandleRegistry registry = new BusinessResourceHandleRegistry(
                repository, blockingBlobs, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-resolve-race", "instance-resolve-race",
                "desktop-resolve-race", "ws-resolve-race");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth-resolve-race", "instance-resolve-race", "desktop-resolve-race",
                "ws-resolve-race", "user-resolve-race", "tenant-resolve-race", "2",
                17, "credential-resolve-race", 1, now);
        var descriptor = registry.register(
                connection, lease, "image/png", new byte[]{4, 5, 6}, Duration.ofMinutes(1));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var resolve = executor.submit(() -> {
                return registry.resolve(descriptor.handle(), connection, lease);
            });
            assertThat(bytesLoaded.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = executor.submit(() -> {
                try {
                    return registry.revoke(connection, lease);
                } finally {
                    revokeReturned.countDown();
                }
            });

            try {
                assertThat(revokeReturned.await(250, TimeUnit.MILLISECONDS))
                        .as("revoke must not return while a successful resolve can still return bytes")
                        .isFalse();
            } finally {
                allowResolveReturn.countDown();
            }

            assertThat(resolve.get(5, TimeUnit.SECONDS)).get()
                    .extracting(BusinessResourceHandleRegistry.StoredResource::bytes)
                    .isEqualTo(new byte[]{4, 5, 6});
            assertThat(revoke.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
    }

    private void create(String id,
                         BusinessAttachmentTicketService.TicketStatus ticketStatus,
                         BusinessAttachmentTicketService.BatchStatus batchStatus,
                         Instant now) {
        create(id, "auth-revoke", ticketStatus, batchStatus, now);
    }

    private void create(String id,
                        String authSessionId,
                        BusinessAttachmentTicketService.TicketStatus ticketStatus,
                        BusinessAttachmentTicketService.BatchStatus batchStatus,
                        Instant now) {
        repository.create(
                new BusinessAttachmentBatchRecord(
                        id, "instance-revoke", "desktop-revoke", authSessionId, "tenant-revoke", 11,
                        "SCHEDULE_CREATE", "operation-" + id, "actor-" + authSessionId,
                        "PERSONAL", null, "schedule-type", "CASE", "case-" + id, "form-" + id,
                        "keystore://declaration-" + id, batchStatus,
                        batchStatus == BusinessAttachmentTicketService.BatchStatus.READY
                                || batchStatus == BusinessAttachmentTicketService.BatchStatus.CONSUMING
                                ? "keystore://fileIds-" + id : null,
                        now.plusSeconds(60), now, now),
                new BusinessAttachmentTicketRecord(
                        "ticket-" + id, id, "instance-revoke", "desktop-revoke", authSessionId,
                        "tenant-revoke", 11, ticketStatus, now.plusSeconds(60),
                        ticketStatus == BusinessAttachmentTicketService.TicketStatus.ISSUED ? null : now,
                        null, now));
    }

    private void assertState(String id,
                             BusinessAttachmentTicketService.TicketStatus ticketStatus,
                             BusinessAttachmentTicketService.BatchStatus batchStatus) {
        assertThat(repository.findTicketByBatchId(id).orElseThrow().state()).isEqualTo(ticketStatus);
        assertThat(repository.findBatch(id).orElseThrow().state()).isEqualTo(batchStatus);
    }
}
