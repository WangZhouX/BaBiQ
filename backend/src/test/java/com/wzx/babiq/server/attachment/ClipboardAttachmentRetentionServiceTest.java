package com.wzx.babiq.server.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.recovery.StartupRecoveryCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClipboardAttachmentRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void cleanupDeletesOnlyExpiredOrphanGeneratedFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path expired = generated(root, "截图-20260718-120000-23457Q.png", Duration.ofHours(25));
        Path recent = generated(root, "截图-20260720-120000-34567R.png", Duration.ofHours(23));
        Path unrelated = oldFile(root.resolve("notes.png"), Duration.ofDays(10));
        Path wrongAlphabet = oldFile(root.resolve("截图-20260718-120000-10OILQ.png"), Duration.ofDays(10));

        ClipboardAttachmentRetentionService.CleanupResult result =
                service(root, List.of()).cleanup();

        assertThat(expired).doesNotExist();
        assertThat(recent).exists();
        assertThat(unrelated).exists();
        assertThat(wrongAlphabet).exists();
        assertThat(result.deletedFiles()).isEqualTo(1);
    }

    @Test
    void activeThreadReferenceAlwaysRetainsGeneratedFile() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path active = generated(root, "截图-20260101-000000-45678S.png", Duration.ofDays(200));

        ClipboardAttachmentRetentionService.CleanupResult result = service(
                root,
                List.of(reference(active, null))).cleanup();

        assertThat(active).exists();
        assertThat(result.deletedFiles()).isZero();
    }

    @Test
    void archivedReferencesExpireOnlyAfterThirtyDaysAndStrongestReferenceWins() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path expired = generated(root, "截图-20260101-000000-56789T.png", Duration.ofDays(200));
        Path recentArchive = generated(root, "截图-20260102-000000-6789AU.png", Duration.ofDays(200));
        Path multiple = generated(root, "截图-20260103-000000-789ABV.png", Duration.ofDays(200));

        service(root, List.of(
                reference(expired, NOW.minus(Duration.ofDays(31))),
                reference(recentArchive, NOW.minus(Duration.ofDays(29))),
                reference(multiple, NOW.minus(Duration.ofDays(90))),
                reference(multiple, null))).cleanup();

        assertThat(expired).doesNotExist();
        assertThat(recentArchive).exists();
        assertThat(multiple).exists();
    }

    @Test
    void cleanupRemovesEligibleFilesButNeverActiveReferencesForCapacityRelief() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path orphan = generated(root, "截图-20260104-000000-89ABCW.png", Duration.ofDays(2));
        Path archived = generated(root, "截图-20260105-000000-9ABCDX.png", Duration.ofDays(90));
        Path active = generated(root, "截图-20260106-000000-ABCDEF.png", Duration.ofDays(90));

        service(root, List.of(
                reference(archived, NOW.minus(Duration.ofDays(31))),
                reference(active, null))).cleanup();

        assertThat(orphan).doesNotExist();
        assertThat(archived).doesNotExist();
        assertThat(active).exists();
    }

    @Test
    void cleanupIgnoresOutsidePathsAndNeverDeletesUserSelectedFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path selected = oldFile(root.resolve("合同.png"), Duration.ofDays(90));
        Path outside = oldFile(
                tempDir.resolve("截图-20260107-000000-BCDEFH.png"),
                Duration.ofDays(90));

        service(root, List.of(reference(outside, NOW.minus(Duration.ofDays(90))))).cleanup();

        assertThat(selected).exists();
        assertThat(outside).exists();
    }

    @Test
    void cleanupDoesNotFollowSymbolicLinks() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path outside = oldFile(tempDir.resolve("outside.png"), Duration.ofDays(90));
        Path link = root.resolve("截图-20260108-000000-CDEFHJ.png");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "symbolic links unavailable: " + exception.getClass().getSimpleName());
        }

        service(root, List.of()).cleanup();

        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(outside).exists();
    }

    @Test
    void malformedPersistedPayloadFailsClosedWithoutDeletingAnyCandidate() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path orphan = generated(root, "截图-20260109-000000-DEFHJK.png", Duration.ofDays(2));

        ClipboardAttachmentRetentionService.CleanupResult result = service(
                root,
                List.of(new AttachmentReferenceRecord("{not-json", null))).cleanup();

        assertThat(orphan).exists();
        assertThat(result.invalidReferenceRecords()).isEqualTo(1);
    }

    @Test
    void structurallyInvalidAttachmentPayloadsFailClosedWhileMissingFieldIsLegacyCompatible()
            throws Exception {
        List<String> invalidPayloads = List.of(
                "[]",
                "{\"attachments\":null}",
                "{\"attachments\":{}}",
                "{\"attachments\":[\"not-an-object\"]}");
        for (int index = 0; index < invalidPayloads.size(); index++) {
            Path root = Files.createDirectories(tempDir.resolve("structural-" + index));
            Path orphan = generated(
                    root,
                    "截图-20260112-00000" + index + "-HJKLMN.png",
                    Duration.ofDays(2));

            ClipboardAttachmentRetentionService.CleanupResult result = service(
                    root,
                    List.of(new AttachmentReferenceRecord(invalidPayloads.get(index), null))).cleanup();

            assertThat(orphan).exists();
            assertThat(result.invalidReferenceRecords()).isEqualTo(1);
        }

        Path legacyRoot = Files.createDirectories(tempDir.resolve("legacy"));
        Path legacyOrphan = generated(
                legacyRoot, "截图-20260112-010000-JKLMNP.png", Duration.ofDays(2));
        ClipboardAttachmentRetentionService.CleanupResult legacyResult = service(
                legacyRoot,
                List.of(new AttachmentReferenceRecord(
                        "{\"id\":\"legacy\",\"type\":\"userMessage\",\"text\":\"old\"}",
                        null))).cleanup();

        assertThat(legacyOrphan).doesNotExist();
        assertThat(legacyResult.invalidReferenceRecords()).isZero();
    }

    @Test
    void invalidClipboardReferencePathFailsClosedForOutsideIllegalAndDotDotPaths() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path orphan = generated(root, "截图-20260113-000000-KLMNPQ.png", Duration.ofDays(2));
        Path outside = oldFile(
                tempDir.resolve("截图-20260113-010000-LMNPQR.png"),
                Duration.ofDays(2));
        String dotDot = root.resolve("nested")
                .resolve("..")
                .resolve(orphan.getFileName())
                .toString();
        String illegalName = root.resolve("not-generated.png").toString();

        List<AttachmentReferenceRecord> invalidReferences = List.of(
                rawReference(outside.toString(), outside.getFileName().toString()),
                rawReference(dotDot, orphan.getFileName().toString()),
                rawReference(illegalName, "not-generated.png"));

        ClipboardAttachmentRetentionService.CleanupResult result =
                service(root, invalidReferences).cleanup();

        assertThat(orphan).exists();
        assertThat(outside).exists();
        assertThat(result.invalidReferenceRecords()).isEqualTo(3);
    }

    @Test
    void referenceQueryFailureFailsClosedWithoutDeletingAnyCandidate() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path orphan = generated(root, "截图-20260110-000000-EFHJKL.png", Duration.ofDays(2));
        AttachmentReferenceRepository failingRepository = () -> {
            throw new IllegalStateException("private database detail");
        };
        ClipboardAttachmentRetentionService service = new ClipboardAttachmentRetentionService(
                failingRepository, new ObjectMapper(), root, CLOCK);

        ClipboardAttachmentRetentionService.CleanupResult result = service.cleanup();

        assertThat(orphan).exists();
        assertThat(result.invalidReferenceRecords()).isEqualTo(1);
    }

    @Test
    void nullReferenceQueryResultFailsClosedWithoutDeletingAnyCandidate() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path orphan = generated(root, "截图-20260110-010000-GHJKLM.png", Duration.ofDays(2));
        ClipboardAttachmentRetentionService service = new ClipboardAttachmentRetentionService(
                () -> null, new ObjectMapper(), root, CLOCK);

        ClipboardAttachmentRetentionService.CleanupResult result = service.cleanup();

        assertThat(orphan).exists();
        assertThat(result.invalidReferenceRecords()).isEqualTo(1);
    }

    @Test
    void selectedFileReferenceIsNeverDeletedEvenWithGeneratedNameAndExpiredArchive() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("attachments/clipboard"));
        Path selected = generated(root, "截图-20260111-000000-FHJKLM.png", Duration.ofDays(90));

        service(root, List.of(reference(
                selected,
                NOW.minus(Duration.ofDays(90)),
                AttachmentSource.SELECTED_FILE))).cleanup();

        assertThat(selected).exists();
    }

    @Test
    void referenceRecordToStringRedactsPersistedPayload() {
        AttachmentReferenceRecord record = new AttachmentReferenceRecord(
                "{\"localPath\":\"C:\\\\private\\\\secret.png\"}",
                "2026-01-01T00:00:00Z");

        assertThat(record.toString())
                .contains("payloadJson=<redacted>")
                .doesNotContain("private", "secret.png");
    }

    @Test
    void fileIdentityFallbackRequiresBothFileKeysToBeAbsent() {
        BasicFileAttributes withoutKey = attributes(null);
        BasicFileAttributes withKey = attributes("file-key");

        assertThat(ClipboardAttachmentRetentionService.sameFileIdentity(withoutKey, withKey))
                .isFalse();
        assertThat(ClipboardAttachmentRetentionService.sameFileIdentity(withoutKey, attributes(null)))
                .isTrue();
    }

    @Test
    void syntheticLinkAndReparseAttributesAreAlwaysRejectedForRootsAndEntries() {
        BasicFileAttributes directory = mock(BasicFileAttributes.class);
        when(directory.isDirectory()).thenReturn(true);
        BasicFileAttributes regular = mock(BasicFileAttributes.class);
        when(regular.isRegularFile()).thenReturn(true);
        BasicFileAttributes reparseDirectory = mock(BasicFileAttributes.class);
        when(reparseDirectory.isDirectory()).thenReturn(true);
        when(reparseDirectory.isOther()).thenReturn(true);
        BasicFileAttributes reparseFile = mock(BasicFileAttributes.class);
        when(reparseFile.isRegularFile()).thenReturn(true);
        when(reparseFile.isOther()).thenReturn(true);

        assertThat(ClipboardAttachmentRetentionService.safeRootAttributes(false, directory)).isTrue();
        assertThat(ClipboardAttachmentRetentionService.safeRootAttributes(true, directory)).isFalse();
        assertThat(ClipboardAttachmentRetentionService.safeRootAttributes(false, reparseDirectory))
                .isFalse();
        assertThat(ClipboardAttachmentRetentionService.safeCandidateAttributes(false, regular)).isTrue();
        assertThat(ClipboardAttachmentRetentionService.safeCandidateAttributes(true, regular)).isFalse();
        assertThat(ClipboardAttachmentRetentionService.safeCandidateAttributes(false, reparseFile))
                .isFalse();
    }

    @Test
    void scheduledCleanupWaitsForStartupRecoveryAndThenReusesCoreCleanup() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("scheduled"));
        AttachmentReferenceRepository repository = mock(AttachmentReferenceRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        StartupRecoveryCoordinator coordinator = mock(StartupRecoveryCoordinator.class);
        ClipboardAttachmentRetentionService service = new ClipboardAttachmentRetentionService(
                repository, new ObjectMapper(), root, CLOCK, coordinator);

        when(coordinator.isRecoveryComplete()).thenReturn(false);
        service.scheduledCleanup();
        verify(repository, never()).findAll();

        when(coordinator.isRecoveryComplete()).thenReturn(true);
        service.scheduledCleanup();
        verify(repository).findAll();
    }

    private ClipboardAttachmentRetentionService service(
            Path root,
            List<AttachmentReferenceRecord> references
    ) {
        return new ClipboardAttachmentRetentionService(
                () -> references,
                new ObjectMapper(),
                root,
                CLOCK);
    }

    private AttachmentReferenceRecord reference(Path path, Instant archivedAt) throws Exception {
        return reference(path, archivedAt, AttachmentSource.CLIPBOARD_IMAGE);
    }

    private AttachmentReferenceRecord reference(
            Path path,
            Instant archivedAt,
            AttachmentSource source
    ) throws Exception {
        AttachmentMetadata metadata = new AttachmentMetadata(
                "550e8400-e29b-41d4-a716-446655440000",
                "A-23457Q",
                path.getFileName().toString(),
                path.toRealPath().toString(),
                "image/png",
                Files.size(path),
                "a".repeat(64),
                source);
        String payload = new ObjectMapper().writeValueAsString(
                UserMessageItem.of("item-1", "attachment", List.of(metadata)));
        return new AttachmentReferenceRecord(
                payload,
                archivedAt == null ? null : archivedAt.toString());
    }

    private AttachmentReferenceRecord rawReference(String localPath, String name) throws Exception {
        AttachmentMetadata metadata = new AttachmentMetadata(
                "550e8400-e29b-41d4-a716-446655440000",
                "A-23457Q",
                name,
                localPath,
                "image/png",
                3,
                "a".repeat(64),
                AttachmentSource.CLIPBOARD_IMAGE);
        return new AttachmentReferenceRecord(
                new ObjectMapper().writeValueAsString(
                        UserMessageItem.of("item-invalid", "attachment", List.of(metadata))),
                null);
    }

    private Path generated(Path root, String name, Duration age) throws IOException {
        return oldFile(root.resolve(name), age);
    }

    private Path oldFile(Path path, Duration age) throws IOException {
        Files.write(path, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(path, FileTime.from(NOW.minus(age)));
        return path;
    }

    private static BasicFileAttributes attributes(Object fileKey) {
        BasicFileAttributes attributes = mock(BasicFileAttributes.class);
        when(attributes.size()).thenReturn(3L);
        when(attributes.lastModifiedTime()).thenReturn(FileTime.from(NOW));
        when(attributes.fileKey()).thenReturn(fileKey);
        return attributes;
    }
}
