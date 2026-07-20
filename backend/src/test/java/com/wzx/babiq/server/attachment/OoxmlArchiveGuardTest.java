package com.wzx.babiq.server.attachment;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlArchiveGuardTest {

    private final OoxmlArchiveGuard guard = new OoxmlArchiveGuard();

    @Test
    void acceptsAReasonableCentralDirectory() {
        assertThatCode(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata("[Content_Types].xml", 512, 300),
                new OoxmlArchiveGuard.EntryMetadata("word/document.xml", 4_000, 1_500)))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsExactEntryCountTotalSingleEntryAndCompressionRatioBoundaries() {
        List<OoxmlArchiveGuard.EntryMetadata> oneThousand = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            oneThousand.add(new OoxmlArchiveGuard.EntryMetadata("entry-" + index, 0, 0));
        }
        assertThatCode(() -> guard.validate(syntheticZip(oneThousand)))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata(
                        "first", 50L * 1024 * 1024, 50L * 1024 * 1024),
                new OoxmlArchiveGuard.EntryMetadata(
                        "second", 50L * 1024 * 1024, 50L * 1024 * 1024)))))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata(
                        "single", 50L * 1024 * 1024, 50L * 1024 * 1024)))))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata("ratio", 10_000, 100)))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMoreThanOneThousandEntries() {
        List<OoxmlArchiveGuard.EntryMetadata> entries = new ArrayList<>();
        for (int index = 0; index < 1_001; index++) {
            entries.add(new OoxmlArchiveGuard.EntryMetadata("entry-" + index, 0, 0));
        }
        assertUnsafe(() -> guard.validate(syntheticZip(entries)));
    }

    @Test
    void rejectsDeclaredTotalSingleEntryAndCompressionRatioLimits() {
        assertUnsafe(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata("first", 50L * 1024 * 1024, 50L * 1024 * 1024),
                new OoxmlArchiveGuard.EntryMetadata("second", 50L * 1024 * 1024, 50L * 1024 * 1024),
                new OoxmlArchiveGuard.EntryMetadata("third", 1, 1)))));
        assertUnsafe(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata(
                        "large", 50L * 1024 * 1024 + 1, 50L * 1024 * 1024)))));
        assertUnsafe(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata("bomb", 10_001, 100)))));
        assertUnsafe(() -> guard.validate(syntheticZip(List.of(
                new OoxmlArchiveGuard.EntryMetadata("zero-compressed", 1, 0)))));
    }

    private static byte[] syntheticZip(List<OoxmlArchiveGuard.EntryMetadata> entries) {
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        for (OoxmlArchiveGuard.EntryMetadata entry : entries) {
            byte[] name = entry.name().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeInt(directory, 0x02014b50);
            writeShort(directory, 20);
            writeShort(directory, 20);
            writeShort(directory, 1 << 11);
            writeShort(directory, 0);
            writeShort(directory, 0);
            writeShort(directory, 0);
            writeInt(directory, 0);
            writeInt(directory, entry.compressedSize());
            writeInt(directory, entry.size());
            writeShort(directory, name.length);
            writeShort(directory, 0);
            writeShort(directory, 0);
            writeShort(directory, 0);
            writeShort(directory, 0);
            writeInt(directory, 0);
            writeInt(directory, 0);
            directory.writeBytes(name);
        }
        int directorySize = directory.size();
        writeInt(directory, 0x06054b50);
        writeShort(directory, 0);
        writeShort(directory, 0);
        writeShort(directory, entries.size());
        writeShort(directory, entries.size());
        writeInt(directory, directorySize);
        writeInt(directory, 0);
        writeShort(directory, 0);
        return directory.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
        output.write((int) (value >>> 16) & 0xff);
        output.write((int) (value >>> 24) & 0xff);
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
