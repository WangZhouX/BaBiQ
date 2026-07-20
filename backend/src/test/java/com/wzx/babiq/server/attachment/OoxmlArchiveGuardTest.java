package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlArchiveGuardTest {

    private final OoxmlArchiveGuard guard = new OoxmlArchiveGuard();

    @Test
    void acceptsAReasonableCentralDirectory() {
        assertThatCode(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("[Content_Types].xml", 512, 300),
                new OoxmlArchiveGuard.EntryMetadata("word/document.xml", 4_000, 1_500))))
                .doesNotThrowAnyException();
    }

    @Test
    void readsTheRealZipCentralDirectoryWithoutInflatingEntries() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document>safe</document>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatCode(() -> guard.validate(output.toByteArray()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMoreThanOneThousandEntries() {
        List<OoxmlArchiveGuard.EntryMetadata> entries = new ArrayList<>();
        for (int index = 0; index < 1_001; index++) {
            entries.add(new OoxmlArchiveGuard.EntryMetadata("entry-" + index, 0, 0));
        }
        assertUnsafe(() -> guard.validateEntries(entries));
    }

    @Test
    void rejectsDeclaredTotalSingleEntryAndCompressionRatioLimits() {
        assertUnsafe(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("first", 50L * 1024 * 1024, 50L * 1024 * 1024),
                new OoxmlArchiveGuard.EntryMetadata("second", 50L * 1024 * 1024, 50L * 1024 * 1024),
                new OoxmlArchiveGuard.EntryMetadata("third", 1, 1))));
        assertUnsafe(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("large", 50L * 1024 * 1024 + 1, 50L * 1024 * 1024))));
        assertUnsafe(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("bomb", 10_001, 100))));
        assertUnsafe(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("zero-compressed", 1, 0))));
    }

    @Test
    void rejectsUnknownOrNegativeCentralDirectorySizes() {
        assertUnsafe(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("unknown", -1, 1))));
        assertUnsafe(() -> guard.validateEntries(List.of(
                new OoxmlArchiveGuard.EntryMetadata("unknown", 1, -1))));
    }

    private static void assertUnsafe(ThrowingAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AttachmentException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_ARCHIVE_UNSAFE))
                .hasMessageNotContaining("\\")
                .hasMessageNotContaining("/");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
