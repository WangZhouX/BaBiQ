package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tika.Tika;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Streams a claimed ticket to bounded local files before invoking the server-owned OA upload port. */
@RestController
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class BusinessAttachmentUploadController {
    private static final String TICKET_HEADER = "X-Business-Upload-Ticket";
    private final BusinessAttachmentTicketService tickets;
    private final ObjectProvider<BusinessAttachmentRemoteUploader> remoteUploader;
    private final Path uploadRoot;
    private final Tika tika = new Tika();

    public BusinessAttachmentUploadController(BusinessAttachmentTicketService tickets,
                                               ObjectProvider<BusinessAttachmentRemoteUploader> remoteUploader,
                                               @Value("${babiq.business.runtime-dir:}") String runtimeDir) {
        this.tickets = tickets;
        this.remoteUploader = remoteUploader;
        this.uploadRoot = runtimeDir == null || runtimeDir.isBlank() ? null : Path.of(runtimeDir).toAbsolutePath().normalize().resolve("attachments").resolve("uploads");
    }

    @PostMapping(path = "/business/attachments/uploads/{batchId}", consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> upload(@PathVariable String batchId,
                                                 @RequestHeader(TICKET_HEADER) String ticket,
                                                 @RequestPart("files") List<MultipartFile> files,
                                                 HttpServletRequest request) {
        TrustedDesktopConnection connection = attribute(request, BusinessLoopbackHttpSecurityFilter.CONNECTION_ATTRIBUTE, TrustedDesktopConnection.class);
        ReadyOaSessionLease lease = attribute(request, BusinessLoopbackHttpSecurityFilter.LEASE_ATTRIBUTE, ReadyOaSessionLease.class);
        BusinessAttachmentTicketService.UploadClaim claim = attribute(request,
                BusinessLoopbackHttpSecurityFilter.UPLOAD_CLAIM_ATTRIBUTE,
                BusinessAttachmentTicketService.UploadClaim.class);
        if (!claim.batchId().equals(batchId)) throw new BusinessUploadUnavailableException();
        List<Path> tempFiles = new ArrayList<>();
        try {
            if (uploadRoot == null) throw new BusinessUploadUnavailableException();
            if (files == null || files.isEmpty() || files.size() > BusinessAttachmentTicketService.MAX_FILE_COUNT) {
                throw new BusinessUploadRejectedException();
            }
            List<BusinessAttachmentRemoteUploader.StagedFile> staged = new ArrayList<>();
            List<BusinessAttachmentTicketService.UploadedFile> metadata = new ArrayList<>();
            long totalBytes = 0;
            for (MultipartFile file : files) {
                Path temp = BusinessUploadPathGuard.createStagedFile(uploadRoot);
                tempFiles.add(temp);
                ScanResult scan = copyAndScan(file, temp);
                totalBytes = Math.addExact(totalBytes, scan.size());
                if (totalBytes >= BusinessAttachmentTicketService.MAX_TOTAL_BYTES) {
                    throw new BusinessUploadRejectedException();
                }
                String safeName = BusinessAttachmentTicketService.sanitizeFileName(file.getOriginalFilename());
                staged.add(new BusinessAttachmentRemoteUploader.StagedFile(safeName, scan.mediaType(), scan.size(), scan.sha256(), temp));
                metadata.add(new BusinessAttachmentTicketService.UploadedFile(safeName, scan.size(), scan.mediaType(), scan.sha256()));
            }
            BusinessAttachmentRemoteUploader uploader = remoteUploader.getIfAvailable();
            if (uploader == null) {
                tickets.outcomeUnknown(claim);
                throw new BusinessUploadUnavailableException();
            }
            tickets.validateBeforeRemote(claim, metadata);
            try (BusinessAttachmentRemoteUploader.UploadedRemoteFiles remoteFiles =
                         uploader.upload(lease, List.copyOf(staged))) {
                BusinessAttachmentTicketService.UploadReceipt receipt;
                try {
                    receipt = tickets.complete(claim, metadata, remoteFiles);
                } catch (BusinessAttachmentTicketService.TicketRejectedException staleAfterRemote) {
                    // The OA may already have accepted bytes when the desktop generation changed.
                    tickets.outcomeUnknown(claim);
                    throw new BusinessUploadOutcomeUnknownException();
                }
                return ResponseEntity.ok(new UploadResponse(receipt.batchId(), receipt.fileCount()));
            } catch (RuntimeException remoteFailure) {
                tickets.outcomeUnknown(claim);
                throw new BusinessUploadOutcomeUnknownException();
            }
        } catch (BusinessUploadUnavailableException unavailable) {
            throw unavailable;
        } catch (BusinessUploadOutcomeUnknownException unknown) {
            throw unknown;
        } catch (BusinessAttachmentTicketService.TicketRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException | IOException failure) {
            tickets.reject(claim);
            throw new BusinessUploadRejectedException();
        } finally {
            for (Path temp : tempFiles) {
                try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            }
        }
    }

    private ScanResult copyAndScan(MultipartFile source, Path target) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception failure) { throw new IllegalStateException("SHA-256 is unavailable", failure); }
        long total = 0;
        BusinessUploadPathGuard.Identity identity = BusinessUploadPathGuard.capture(target);
        try (InputStream input = new DigestInputStream(source.getInputStream(), digest);
             var channel = BusinessUploadPathGuard.openForWrite(target);
             var output = java.nio.channels.Channels.newOutputStream(channel)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total >= BusinessAttachmentTicketService.MAX_SINGLE_BYTES) throw new BusinessUploadRejectedException();
                output.write(buffer, 0, read);
            }
        }
        BusinessUploadPathGuard.verifySame(identity);
        if (total <= 0) throw new BusinessUploadRejectedException();
        return new ScanResult(total, tika.detect(target), HexFormat.of().formatHex(digest.digest()));
    }

    private static <T> T attribute(HttpServletRequest request, String key, Class<T> type) {
        Object value = request.getAttribute(key);
        if (!type.isInstance(value)) throw new BusinessUploadUnavailableException();
        return type.cast(value);
    }

    private record ScanResult(long size, String mediaType, String sha256) { }
    public record UploadResponse(String attachmentBatchId, int fileCount) { }
    static class BusinessUploadUnavailableException extends RuntimeException { }
    static class BusinessUploadRejectedException extends RuntimeException { }
    static class BusinessUploadOutcomeUnknownException extends RuntimeException { }
}
