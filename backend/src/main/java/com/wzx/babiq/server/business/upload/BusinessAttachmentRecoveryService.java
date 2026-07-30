package com.wzx.babiq.server.business.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Startup cleanup for expired ticket/resource state and orphaned upload parts. */
@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessAttachmentRecoveryService {
    private final BusinessAttachmentTicketService tickets;
    private final BusinessResourceHandleRegistry resources;
    private final Path uploadRoot;

    public BusinessAttachmentRecoveryService(BusinessAttachmentTicketService tickets,
                                              BusinessResourceHandleRegistry resources,
                                              @Value("${babiq.business.runtime-dir:}") String runtimeDir) {
        this.tickets = tickets;
        this.resources = resources;
        this.uploadRoot = runtimeDir == null || runtimeDir.isBlank() ? null
                : Path.of(runtimeDir).toAbsolutePath().normalize().resolve("attachments").resolve("uploads");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() { recover(); }

    public RecoveryReport recover() {
        int expiredTickets = tickets.purgeExpired();
        int expiredResources = resources.purgeExpired();
        int deletedFiles = deleteOrphanedFiles();
        return new RecoveryReport(expiredTickets, expiredResources, deletedFiles);
    }

    private int deleteOrphanedFiles() {
        if (uploadRoot == null || !Files.isDirectory(uploadRoot, LinkOption.NOFOLLOW_LINKS)) return 0;
        int[] count = {0};
        try (Stream<Path> files = Files.list(uploadRoot)) {
            files.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().startsWith("upload-"))
                    .forEach(path -> {
                        try { if (Files.deleteIfExists(path)) count[0]++; }
                        catch (IOException ignored) { }
                    });
        } catch (IOException ignored) { }
        return count[0];
    }

    public record RecoveryReport(int expiredTickets, int expiredResources, int deletedFiles) { }
}
