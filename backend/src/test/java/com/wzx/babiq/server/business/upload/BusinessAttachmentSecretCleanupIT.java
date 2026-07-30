package com.wzx.babiq.server.business.upload;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = "babiq.business.enabled=false")
class BusinessAttachmentSecretCleanupIT {
    private static final Path DATABASE = Path.of("target", "test-db",
            "business-attachment-cleanup-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", DATABASE::toString);
    }

    @Autowired private BusinessAttachmentSecretCleanupRepository repository;
    @Autowired private DataSource dataSource;

    @Test
    void failed_secret_deletion_leaves_durable_tombstone_and_later_retry_removes_it() throws Exception {
        BusinessAttachmentFileIdStore secrets = mock(BusinessAttachmentFileIdStore.class);
        doThrow(new IllegalStateException("canary-sensitive-message"))
                .doNothing().when(secrets).delete("keystore://business.attachment.fileIds.opaque");
        BusinessAttachmentSecretCleanupService cleanup =
                new BusinessAttachmentSecretCleanupService(repository, secrets, Clock.systemUTC());

        cleanup.scheduleAndAttempt("keystore://business.attachment.fileIds.opaque",
                "FILE_IDS", "ATTACHMENT_CONSUMED");

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT attempt_count, last_result_code
                     FROM bq_business_attachment_secret_cleanup
                     WHERE secret_ref = ?
                     """)) {
            statement.setString(1, "keystore://business.attachment.fileIds.opaque");
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(1);
                assertThat(row.getString(2)).isEqualTo("SECRET_STORE_DELETE_FAILED");
            }
        }

        assertThat(cleanup.drainPending()).isEqualTo(
                new BusinessAttachmentSecretCleanupService.DrainReport(1, 1, 0));
        assertThat(repository.listPending(10)).isEmpty();
    }
}
