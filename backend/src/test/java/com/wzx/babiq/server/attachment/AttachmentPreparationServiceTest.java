package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AttachmentPreparationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsMoreThanEightAttachmentsAndDuplicateOrMalformedIdsBeforeReadingFiles() {
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentPreparationService service = new AttachmentPreparationService(
                validator, tempDir.resolve("clipboard"));

        List<AttachmentRequest> tooMany = new ArrayList<>();
        for (int index = 0; index < AttachmentLimits.MAX_ATTACHMENTS + 1; index++) {
            tooMany.add(request(tempDir.resolve("file-" + index + ".txt"), displayId(index)));
        }
        assertCode(service, tooMany, AttachmentErrorCode.ATTACHMENT_LIMIT_EXCEEDED);

        AttachmentRequest valid = request(tempDir.resolve("one.txt"), "A-234567");
        assertCode(service, List.of(valid, valid), AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS);
        assertCode(service, List.of(
                        valid,
                        new AttachmentRequest(
                                UUID.randomUUID().toString(), valid.displayId(), "two.txt",
                                tempDir.resolve("two.txt").toString())),
                AttachmentErrorCode.ATTACHMENT_REFERENCE_AMBIGUOUS);
        assertCode(service, List.of(
                        new AttachmentRequest("not-a-uuid", "A-234568", "one.txt",
                                tempDir.resolve("one.txt").toString())),
                AttachmentErrorCode.ATTACHMENT_PATH_INVALID);
        assertCode(service, List.of(
                        new AttachmentRequest(UUID.randomUUID().toString(), "A-01IOZZ", "one.txt",
                                tempDir.resolve("one.txt").toString())),
                AttachmentErrorCode.ATTACHMENT_PATH_INVALID);

        verifyNoInteractions(validator);
    }

    @Test
    void enforcesFiftyMebibyteTotalAfterIndividuallyValidFiles() {
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        Path clipboard = tempDir.resolve("clipboard");
        AttachmentPreparationService service = new AttachmentPreparationService(validator, clipboard);
        List<AttachmentRequest> requests = List.of(
                request(tempDir.resolve("one.txt"), "A-234567"),
                request(tempDir.resolve("two.txt"), "A-234568"),
                request(tempDir.resolve("three.txt"), "A-234569")
        );
        for (int index = 0; index < requests.size(); index++) {
            AttachmentRequest request = requests.get(index);
            when(validator.validate(request)).thenReturn(
                    prepared(request, tempDir.resolve("canonical-" + index + ".txt"),
                            18L * 1024 * 1024, "text/plain"));
        }

        assertCode(service, requests, AttachmentErrorCode.ATTACHMENT_TOTAL_TOO_LARGE);
    }

    @Test
    void derivesClipboardSourceOnlyForImagesInsideControlledCanonicalRoot() throws Exception {
        Path clipboard = Files.createDirectories(tempDir.resolve("runtime/attachments/clipboard")).toRealPath();
        Path image = Files.write(clipboard.resolve("capture.png"), new byte[]{1});
        Path document = Files.write(clipboard.resolve("note.txt"), new byte[]{1});
        Path selectedImage = Files.write(tempDir.resolve("selected.png"), new byte[]{1});
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        AttachmentPreparationService service = new AttachmentPreparationService(validator, clipboard);
        List<AttachmentRequest> requests = List.of(
                request(image, "A-234567"),
                request(document, "A-234568"),
                request(selectedImage, "A-234569")
        );
        when(validator.validate(requests.get(0))).thenReturn(prepared(requests.get(0), image, 1, "image/png"));
        when(validator.validate(requests.get(1))).thenReturn(prepared(requests.get(1), document, 1, "text/plain"));
        when(validator.validate(requests.get(2))).thenReturn(
                prepared(requests.get(2), selectedImage, 1, "image/png"));

        PreparedTurnInput input = service.prepareNew("review", requests);

        assertThat(input.newAttachments())
                .extracting(attachment -> attachment.metadata().source())
                .containsExactly(
                        AttachmentSource.CLIPBOARD_IMAGE,
                        AttachmentSource.SELECTED_FILE,
                        AttachmentSource.SELECTED_FILE);
        assertThat(input.referencedAttachments()).isEmpty();
        assertThat(input.allAttachments()).containsExactlyElementsOf(input.newAttachments());
    }

    @Test
    void missingClipboardRootFailsClosedInsteadOfTrustingAStringPrefix() {
        Path missingRoot = tempDir.resolve("missing-runtime/attachments/clipboard");
        Path apparentChild = missingRoot.resolve("capture.png").toAbsolutePath().normalize();
        AttachmentRequest request = request(apparentChild, "A-23457T");
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        when(validator.validate(request)).thenReturn(prepared(request, apparentChild, 1, "image/png"));
        AttachmentPreparationService service = new AttachmentPreparationService(validator, missingRoot);

        PreparedTurnInput input = service.prepareNew("", List.of(request));

        assertThat(input.newAttachments().getFirst().metadata().source())
                .isEqualTo(AttachmentSource.SELECTED_FILE);
    }

    @Test
    void comparesAgainstTheCanonicalClipboardRootInsteadOfItsConfiguredAlias() throws Exception {
        Path realRoot = Files.createDirectories(tempDir.resolve("real-runtime/attachments/clipboard")).toRealPath();
        Path configuredAlias = tempDir.resolve("configured-alias");
        Path image = Files.write(realRoot.resolve("capture.png"), new byte[]{1});
        AttachmentRequest request = request(image, "A-23457U");
        AttachmentFileValidator validator = mock(AttachmentFileValidator.class);
        when(validator.validate(request)).thenReturn(prepared(request, image, 1, "image/png"));
        AttachmentPreparationService service = new AttachmentPreparationService(
                validator,
                configuredAlias,
                ignored -> realRoot);

        PreparedTurnInput input = service.prepareNew("", List.of(request));

        assertThat(input.newAttachments().getFirst().metadata().source())
                .isEqualTo(AttachmentSource.CLIPBOARD_IMAGE);
    }

    @Test
    void preparedTurnInputKeepsNewAndReferencedAttachmentsSeparateAndDeduplicatesCombinedOrder() {
        PreparedAttachment first = prepared(
                request(tempDir.resolve("first.txt"), "A-234567"),
                tempDir.resolve("first.txt"), 1, "text/plain");
        PreparedAttachment second = prepared(
                request(tempDir.resolve("second.txt"), "A-234568"),
                tempDir.resolve("second.txt"), 1, "text/plain");
        PreparedAttachment third = prepared(
                request(tempDir.resolve("third.txt"), "A-234569"),
                tempDir.resolve("third.txt"), 1, "text/plain");

        PreparedTurnInput input = new PreparedTurnInput(
                "use attachments", List.of(first, second), List.of(second, third));

        assertThat(input.newAttachments()).containsExactly(first, second);
        assertThat(input.referencedAttachments()).containsExactly(second, third);
        assertThat(input.allAttachments()).containsExactly(first, second, third);
        assertThat(input.toString())
                .doesNotContain(first.canonicalPath().toString())
                .doesNotContain(second.canonicalPath().toString());
    }

    private void assertCode(
            AttachmentPreparationService service,
            List<AttachmentRequest> requests,
            AttachmentErrorCode code
    ) {
        assertThatThrownBy(() -> service.prepareNew("text", requests))
                .isInstanceOf(AttachmentException.class)
                .extracting(error -> ((AttachmentException) error).code())
                .isEqualTo(code);
    }

    private AttachmentRequest request(Path path, String displayId) {
        return new AttachmentRequest(
                UUID.randomUUID().toString(), displayId, path.getFileName().toString(), path.toString());
    }

    private PreparedAttachment prepared(
            AttachmentRequest request,
            Path path,
            long sizeBytes,
            String mediaType
    ) {
        Path canonical = path.toAbsolutePath().normalize();
        AttachmentMetadata metadata = new AttachmentMetadata(
                request.id(),
                request.displayId(),
                path.getFileName().toString(),
                canonical.toString(),
                mediaType,
                sizeBytes,
                "a".repeat(64),
                AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(
                metadata,
                canonical,
                new PreparedAttachment.FileIdentity(sizeBytes, FileTime.fromMillis(1234), "file-key"));
    }

    private static String displayId(int index) {
        final String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        int value = index + 2;
        StringBuilder builder = new StringBuilder("A-");
        for (int position = 0; position < 6; position++) {
            builder.append(alphabet.charAt(value % alphabet.length()));
            value /= alphabet.length();
        }
        return builder.toString();
    }
}
