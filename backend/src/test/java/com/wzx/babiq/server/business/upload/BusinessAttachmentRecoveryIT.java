package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "babiq.business.enabled=false")
class BusinessAttachmentRecoveryIT {
    private static final Path DATABASE = Path.of("target", "test-db",
            "business-attachment-recovery-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", DATABASE::toString);
    }

    @Autowired BusinessAttachmentRepository repository;
    @Autowired SecretStore secretStore;

    @Test
    void restart_terminalizes_issued_ticket_and_deletes_declaration_secret_without_replay() {
        BusinessAttachmentFileIdStore secrets = new BusinessAttachmentFileIdStore(secretStore);
        BusinessAttachmentTicketService service = new BusinessAttachmentTicketService(repository, secrets);
        TrustedDesktopConnection connection =
                new TrustedDesktopConnection("reservation-r", "instance-r", "desktop-r", "ws-r");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth-r", "instance-r", "desktop-r", "ws-r", "user-r", "tenant-r", "2",
                11, "credential-r", 1, Instant.now());
        var prepared = service.prepare(connection, lease, "SCHEDULE_CREATE", "operation-r",
                "user-r", "PERSONAL", null, "type-r", "CASE", "case-r", "7",
                List.of(new BusinessAttachmentTicketService.FileDeclaration(
                        "r.pdf", 3, "application/pdf", null)));
        String declarationRef = repository.findBatch(prepared.batchId()).orElseThrow().declarationSecretRef();
        assertThat(secretStore.loadChars(declarationRef)).isPresent();

        service.purgeExpired();

        assertThat(repository.findTicketByBatchId(prepared.batchId()).orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.TicketStatus.REVOKED);
        assertThat(repository.findBatch(prepared.batchId()).orElseThrow().state())
                .isEqualTo(BusinessAttachmentTicketService.BatchStatus.REVOKED);
        assertThat(secretStore.loadChars(declarationRef)).isEmpty();
    }
}
