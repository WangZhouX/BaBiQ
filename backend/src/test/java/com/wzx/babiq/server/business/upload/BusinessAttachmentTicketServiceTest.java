package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessAttachmentTicketServiceTest {

    @Test
    void prepare_binds_ticket_to_operation_lease_and_parent_without_remote_file_ids() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 4);

        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));

        assertThat(batch.batchId()).doesNotContain("case-1", "tenant-1");
        assertThat(batch.ticket()).isNotEqualTo(batch.batchId());
        assertThat(batch.expiresAt()).isEqualTo(Instant.parse("2026-07-27T00:01:00Z"));
        assertThat(batch.toString()).doesNotContain(batch.ticket());
    }

    @Test
    void only_one_request_can_claim_a_ticket_and_validation_must_match_declarations() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));

        BusinessAttachmentTicketService.UploadClaim claim = service.claim(batch.batchId(), batch.ticket(), connection, lease);
        assertThat(service.tryClaim(batch.batchId(), batch.ticket(), connection, lease)).isEmpty();
        assertThatThrownBy(() -> service.complete(claim, List.of(
                        new BusinessAttachmentTicketService.UploadedFile("brief.pdf", 2, "application/pdf", "bad"))))
                .isInstanceOf(BusinessAttachmentTicketService.TicketRejectedException.class);
        assertThat(service.status(batch.batchId()).ticket()).isEqualTo(BusinessAttachmentTicketService.TicketStatus.REJECTED);
        assertThat(service.status(batch.batchId()).batch()).isEqualTo(BusinessAttachmentTicketService.BatchStatus.FAILED);
    }

    @Test
    void successful_upload_returns_batch_receipt_and_is_not_reusable() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        String hash = BusinessAttachmentTicketService.sha256Hex(new byte[]{1, 2, 3});
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", hash)));
        BusinessAttachmentTicketService.UploadClaim claim = service.claim(batch.batchId(), batch.ticket(), connection, lease);

        BusinessAttachmentTicketService.UploadReceipt receipt = service.complete(claim, List.of(
                new BusinessAttachmentTicketService.UploadedFile("brief.pdf", 3, "application/pdf", hash)));

        assertThat(receipt.batchId()).isEqualTo(batch.batchId());
        assertThat(receipt.fileCount()).isEqualTo(1);
        assertThat(receipt.toString()).doesNotContain(hash, batch.ticket());
        assertThat(service.status(batch.batchId()).ticket()).isEqualTo(BusinessAttachmentTicketService.TicketStatus.SUCCEEDED);
        assertThat(service.tryClaim(batch.batchId(), batch.ticket(), connection, lease)).isEmpty();
    }

    @Test
    void attachment_metadata_diagnostic_strings_do_not_retain_file_name_or_hash() {
        String fileName = "confidential-client-1701.pdf";
        String hash = "a".repeat(64);
        String unsafeMediaType = "application/pdf; token=attachment-secret-canary-1701";

        BusinessAttachmentTicketService.FileDeclaration declaration =
                new BusinessAttachmentTicketService.FileDeclaration(
                        fileName, 3, unsafeMediaType, hash);
        BusinessAttachmentTicketService.UploadedFile uploaded =
                new BusinessAttachmentTicketService.UploadedFile(
                        fileName, 3, unsafeMediaType, hash);

        assertThat(declaration.toString()).doesNotContain(fileName, hash, unsafeMediaType);
        assertThat(uploaded.toString()).doesNotContain(fileName, hash, unsafeMediaType);
    }

    @Test
    void generation_change_and_expiry_revoke_ticket_before_claim() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-27T00:00:00Z"));
        BusinessAttachmentTicketService service = new BusinessAttachmentTicketService(clock);
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));

        assertThat(service.tryClaim(batch.batchId(), batch.ticket(), connection, lease(connection, 2))).isEmpty();
        clock.advance(Duration.ofSeconds(61));
        assertThat(service.tryClaim(batch.batchId(), batch.ticket(), connection, lease)).isEmpty();
        assertThat(service.status(batch.batchId()).ticket()).isEqualTo(BusinessAttachmentTicketService.TicketStatus.EXPIRED);
    }

    @Test
    void successful_batch_can_be_consumed_once_by_matching_schedule_create() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));
        BusinessAttachmentTicketService.UploadClaim claim = service.claim(batch.batchId(), batch.ticket(), connection, lease);
        service.complete(claim, List.of(new BusinessAttachmentTicketService.UploadedFile("brief.pdf", 3, "application/pdf", null)));

        assertThat(service.consumeForScheduleCreate(batch.batchId(), connection, lease, "client-op-1", "case-1")).isTrue();
        assertThat(service.consumeForScheduleCreate(batch.batchId(), connection, lease, "client-op-1", "case-1")).isFalse();
        assertThat(service.status(batch.batchId()).batch()).isEqualTo(BusinessAttachmentTicketService.BatchStatus.CONSUMED);
    }

    @Test
    void preflight_checks_a_successful_batch_without_consuming_it() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));
        BusinessAttachmentTicketService.UploadClaim claim = service.claim(batch.batchId(), batch.ticket(), connection, lease);
        service.complete(claim, List.of(new BusinessAttachmentTicketService.UploadedFile(
                "brief.pdf", 3, "application/pdf", null)));

        assertThat(service.canConsumeForScheduleCreate(batch.batchId(), connection, lease, "client-op-1", "case-1"))
                .isTrue();
        assertThat(service.status(batch.batchId()).batch()).isEqualTo(BusinessAttachmentTicketService.BatchStatus.READY);
        assertThat(service.canConsumeForScheduleCreate(batch.batchId(), connection, lease, "client-op-1", "other-case"))
                .isFalse();
    }

    @Test
    void service_batch_is_bound_to_the_exact_record_and_leaf_pair() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", lease.userId(), "PERSONAL",
                null, "type-1", "SERVICE", "project-1", "record-a", "0",
                List.of(new BusinessAttachmentTicketService.FileDeclaration(
                        "brief.pdf", 3, "application/pdf", null)));
        BusinessAttachmentTicketService.UploadClaim claim =
                service.claim(batch.batchId(), batch.ticket(), connection, lease);
        service.complete(claim, List.of(new BusinessAttachmentTicketService.UploadedFile(
                "brief.pdf", 3, "application/pdf", null)));

        assertThat(service.canConsumeForScheduleCreate(
                batch.batchId(), connection, lease, "client-op-1",
                "PERSONAL", null, "type-1", "project-1", "record-b"))
                .isFalse();
        assertThat(service.canConsumeForScheduleCreate(
                batch.batchId(), connection, lease, "client-op-1",
                "TEAM", "team-1", "type-1", "project-1", "record-a"))
                .isFalse();
        assertThat(service.canConsumeForScheduleCreate(
                batch.batchId(), connection, lease, "client-op-1",
                "PERSONAL", null, "type-2", "project-1", "record-a"))
                .isFalse();
        assertThat(service.canConsumeForScheduleCreate(
                batch.batchId(), connection, lease, "client-op-1",
                "PERSONAL", null, "type-1", "project-1", "record-a"))
                .isTrue();
    }

    @Test
    void actual_metadata_is_validated_before_remote_upload_while_claim_is_in_flight() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));
        BusinessAttachmentTicketService.UploadClaim claim = service.claim(batch.batchId(), batch.ticket(), connection, lease);

        assertThatThrownBy(() -> service.validateBeforeRemote(claim, List.of(
                        new BusinessAttachmentTicketService.UploadedFile("brief.pdf", 2, "application/pdf", null))))
                .isInstanceOf(BusinessAttachmentTicketService.TicketRejectedException.class);
        assertThat(service.status(batch.batchId()).ticket()).isEqualTo(BusinessAttachmentTicketService.TicketStatus.REJECTED);
    }

    @Test
    void remote_upload_failure_is_terminal_outcome_unknown_and_batch_is_not_ready() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch batch = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));
        BusinessAttachmentTicketService.UploadClaim claim = service.claim(batch.batchId(), batch.ticket(), connection, lease);

        service.outcomeUnknown(claim);

        assertThat(service.status(batch.batchId()).ticket()).isEqualTo(BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN);
        assertThat(service.status(batch.batchId()).batch()).isEqualTo(BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN);
        assertThat(service.consumeForScheduleCreate(batch.batchId(), connection, lease, "client-op-1", "case-1")).isFalse();
    }

    @Test
    void connection_revocation_preserves_unknown_in_flight_and_revokes_ready_batch() {
        BusinessAttachmentTicketService service = service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease lease = lease(connection, 1);
        BusinessAttachmentTicketService.PreparedBatch inFlight = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-1", "case-1", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("brief.pdf", 3, "application/pdf", null)));
        service.claim(inFlight.batchId(), inFlight.ticket(), connection, lease);
        BusinessAttachmentTicketService.PreparedBatch ready = service.prepare(
                connection, lease, "SCHEDULE_CREATE", "client-op-2", "case-2", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration("evidence.pdf", 3, "application/pdf", null)));
        BusinessAttachmentTicketService.UploadClaim readyClaim =
                service.claim(ready.batchId(), ready.ticket(), connection, lease);
        service.complete(readyClaim, List.of(
                new BusinessAttachmentTicketService.UploadedFile("evidence.pdf", 3, "application/pdf", null)));

        assertThat(service.revokeForConnection(connection, lease)).isEqualTo(1);

        assertThat(service.status(inFlight.batchId())).isEqualTo(new BusinessAttachmentTicketService.Status(
                BusinessAttachmentTicketService.TicketStatus.OUTCOME_UNKNOWN,
                BusinessAttachmentTicketService.BatchStatus.OUTCOME_UNKNOWN));
        assertThat(service.status(ready.batchId())).isEqualTo(new BusinessAttachmentTicketService.Status(
                BusinessAttachmentTicketService.TicketStatus.SUCCEEDED,
                BusinessAttachmentTicketService.BatchStatus.REVOKED));
    }

    @Test
    void in_memory_revoke_does_not_touch_replacement_auth_session_with_same_generation() {
        BusinessAttachmentTicketService service =
                service(Instant.parse("2026-07-27T00:00:00Z"));
        TrustedDesktopConnection connection = connection("instance-1", "desktop-1", "ws-1");
        ReadyOaSessionLease oldLease = lease(connection, "auth-old", 1);
        ReadyOaSessionLease replacementLease = lease(connection, "auth-replacement", 1);
        var oldBatch = service.prepare(
                connection, oldLease, "SCHEDULE_CREATE", "client-op-old", "case-old", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration(
                                "old.pdf", 3, "application/pdf", null)));
        var replacementBatch = service.prepare(
                connection, replacementLease, "SCHEDULE_CREATE",
                "client-op-replacement", "case-replacement", List.of(
                        new BusinessAttachmentTicketService.FileDeclaration(
                                "replacement.pdf", 3, "application/pdf", null)));

        assertThat(service.revokeForConnection(connection, oldLease)).isEqualTo(1);

        assertThat(service.status(oldBatch.batchId())).isEqualTo(
                new BusinessAttachmentTicketService.Status(
                        BusinessAttachmentTicketService.TicketStatus.REVOKED,
                        BusinessAttachmentTicketService.BatchStatus.REVOKED));
        assertThat(service.status(replacementBatch.batchId())).isEqualTo(
                new BusinessAttachmentTicketService.Status(
                        BusinessAttachmentTicketService.TicketStatus.ISSUED,
                        BusinessAttachmentTicketService.BatchStatus.PENDING));
    }

    private static BusinessAttachmentTicketService service(Instant now) {
        return new BusinessAttachmentTicketService(Clock.fixed(now, ZoneOffset.UTC));
    }
    private static TrustedDesktopConnection connection(String instance, String session, String ws) {
        return new TrustedDesktopConnection("reservation-" + instance, instance, session, ws);
    }
    private static ReadyOaSessionLease lease(TrustedDesktopConnection connection, long generation) {
        return lease(connection, "auth-1", generation);
    }
    private static ReadyOaSessionLease lease(
            TrustedDesktopConnection connection,
            String authSessionId,
            long generation) {
        return new ReadyOaSessionLease(authSessionId, connection.desktopInstanceId(), connection.desktopSessionId(),
                connection.webSocketSessionId(), "user-1", "tenant-1", "2", generation,
                "credential-" + generation, 1, Instant.parse("2026-07-27T00:00:00Z"));
    }
    private static final class MutableClock extends Clock {
        private Instant current;
        private MutableClock(Instant current) { this.current = current; }
        private void advance(Duration amount) { current = current.plus(amount); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
