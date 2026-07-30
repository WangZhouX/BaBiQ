package com.wzx.babiq.server.business.upload;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAttachmentRecoveryServiceTest {
    @Test
    void recovery_deletes_orphaned_upload_part_without_replaying_remote_operation() throws Exception {
        Path root = Files.createTempDirectory("babiq-attachment-recovery");
        Path uploadRoot = Files.createDirectories(root.resolve("attachments").resolve("uploads"));
        Path orphan = Files.writeString(uploadRoot.resolve("upload-orphan.part"), "partial");
        Files.writeString(uploadRoot.resolve("keep.txt"), "keep");
        BusinessAttachmentRecoveryService service = new BusinessAttachmentRecoveryService(
                new BusinessAttachmentTicketService(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
                new BusinessResourceHandleRegistry(Clock.fixed(Instant.now(), ZoneOffset.UTC), java.time.Duration.ofMinutes(5)),
                root.toString());

        BusinessAttachmentRecoveryService.RecoveryReport report = service.recover();

        assertThat(report.deletedFiles()).isEqualTo(1);
        assertThat(Files.exists(orphan)).isFalse();
        assertThat(Files.exists(uploadRoot.resolve("keep.txt"))).isTrue();
    }
}
