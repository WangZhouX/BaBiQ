package com.wzx.babiq.server.attachment;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OoxmlArchiveGuardTest {

    private static final int LOCAL_SIGNATURE = 0x04034b50;
    private static final int CENTRAL_SIGNATURE = 0x02014b50;
    private static final int EOCD_SIGNATURE = 0x06054b50;

    private final OoxmlArchiveGuard guard = new OoxmlArchiveGuard();

    @Test
    void acceptsRealPoiDocxAndStandardDataDescriptorZip() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("safe");
            document.write(output);
            docx = output.toByteArray();
        }
        byte[] descriptorZip = zip(List.of(
                new EntrySpec("word/document.xml", 4_096, index -> index & 0xff)));

        assertThat((unsignedShort(descriptorZip, 6) & (1 << 3))).isNotZero();
        assertThatCode(() -> guard.validate(docx)).doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(descriptorZip)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAllExactBoundariesUsingActualEntryData() {
        List<EntrySpec> oneThousand = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            oneThousand.add(new EntrySpec("entry-" + index, 0, ignored -> 0));
        }
        assertThatCode(() -> guard.validate(zip(oneThousand))).doesNotThrowAnyException();

        byte[] entropy = new byte[1024 * 1024];
        new Random(42).nextBytes(entropy);
        ByteGenerator repeatedEntropy = index -> entropy[index % entropy.length] & 0xff;
        EntrySpec fiftyMiB = new EntrySpec(
                "first.bin", 50 * 1024 * 1024, repeatedEntropy);
        EntrySpec anotherFiftyMiB = new EntrySpec(
                "second.bin", 50 * 1024 * 1024, repeatedEntropy);
        assertThatCode(() -> guard.validate(zip(List.of(fiftyMiB))))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate(zip(List.of(fiftyMiB, anotherFiftyMiB))))
                .doesNotThrowAnyException();

        byte[] exactRatio = zip(List.of(new EntrySpec("ratio.bin", 1_200, ignored -> 0)));
        CentralEntry ratioEntry = centralEntries(exactRatio).getFirst();
        assertThat(ratioEntry.size()).isEqualTo(1_200);
        assertThat(ratioEntry.compressedSize()).isEqualTo(12);
        assertThatCode(() -> guard.validate(exactRatio)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEveryLimitImmediatelyAboveItsBoundary() {
        byte[] entropy = new byte[1024 * 1024];
        new Random(84).nextBytes(entropy);
        ByteGenerator repeatedEntropy = index -> entropy[index % entropy.length] & 0xff;
        List<EntrySpec> tooMany = new ArrayList<>();
        for (int index = 0; index < 1_001; index++) {
            tooMany.add(new EntrySpec("entry-" + index, 0, ignored -> 0));
        }
        assertUnsafe(() -> guard.validate(zip(tooMany)));

        byte[] singleTooLarge = zip(List.of(
                new EntrySpec("large.bin", 50 * 1024 * 1024 + 1, repeatedEntropy)));
        assertUnsafe(() -> guard.validate(singleTooLarge));

        byte[] totalTooLarge = zip(List.of(
                new EntrySpec("first.bin", 50 * 1024 * 1024, repeatedEntropy),
                new EntrySpec("second.bin", 50 * 1024 * 1024, repeatedEntropy),
                new EntrySpec("third.bin", 1, ignored -> 1)));
        assertUnsafe(() -> guard.validate(totalTooLarge));

        byte[] ratioTooLarge = zip(List.of(
                new EntrySpec("ratio.bin", 1_201, ignored -> 0)));
        assertThat(centralEntries(ratioTooLarge).getFirst().compressedSize()).isEqualTo(12);
        assertUnsafe(() -> guard.validate(ratioTooLarge));
    }

    @Test
    void rejectsMalformedEocdZip64MultiDiskAndEmptyArchives() {
        byte[] valid = zip(List.of(new EntrySpec("safe.txt", 4, index -> index)));

        byte[] malformedEocd = valid.clone();
        int eocd = findSignature(malformedEocd, EOCD_SIGNATURE, 0);
        malformedEocd[eocd] = 0;
        assertUnsafe(() -> guard.validate(malformedEocd));

        byte[] multiDisk = valid.clone();
        writeShort(multiDisk, eocd + 4, 1);
        assertUnsafe(() -> guard.validate(multiDisk));

        byte[] zip64 = valid.clone();
        int central = findSignature(zip64, CENTRAL_SIGNATURE, 0);
        writeInt(zip64, central + 20, 0xffff_ffffL);
        assertUnsafe(() -> guard.validate(zip64));

        assertUnsafe(() -> guard.validate(zip(List.of())));
    }

    @Test
    void rejectsDuplicateAndDangerousEntryNames() {
        byte[] duplicate = zip(List.of(
                new EntrySpec("a.txt", 1, ignored -> 1),
                new EntrySpec("b.txt", 1, ignored -> 2)));
        replaceAscii(duplicate, "b.txt", "a.txt");
        assertUnsafe(() -> guard.validate(duplicate));

        for (String name : List.of(
                "../evil.xml",
                "word/../evil.xml",
                "/absolute.xml",
                "\\absolute.xml",
                "C:/drive.xml",
                "C:\\drive.xml",
                "word\\document.xml")) {
            byte[] dangerous = zip(List.of(new EntrySpec(name, 1, ignored -> 1)));
            assertUnsafe(() -> guard.validate(dangerous));
        }
    }

    @Test
    void rejectsCentralLocalHeaderAndDataDescriptorMismatches() {
        byte[] base = zip(List.of(new EntrySpec("safe.txt", 8_192, index -> index & 0xff)));
        CentralEntry entry = centralEntries(base).getFirst();

        byte[] nameMismatch = base.clone();
        nameMismatch[entry.localOffset() + 30] ^= 1;
        assertUnsafe(() -> guard.validate(nameMismatch));

        byte[] methodMismatch = base.clone();
        writeShort(methodMismatch, entry.localOffset() + 8, 0);
        assertUnsafe(() -> guard.validate(methodMismatch));

        byte[] flagsMismatch = base.clone();
        writeShort(flagsMismatch, entry.localOffset() + 6, entry.flags() ^ (1 << 11));
        assertUnsafe(() -> guard.validate(flagsMismatch));

        byte[] descriptorMismatch = base.clone();
        int descriptor = entry.dataOffset() + Math.toIntExact(entry.compressedSize());
        assertThat(unsignedInt(descriptorMismatch, descriptor)).isEqualTo(0x08074b50L);
        descriptorMismatch[descriptor + 4] ^= 1;
        assertUnsafe(() -> guard.validate(descriptorMismatch));

        byte[] localOffsetMismatch = base.clone();
        writeInt(localOffsetMismatch, entry.centralOffset() + 42, entry.localOffset() + 1L);
        assertUnsafe(() -> guard.validate(localOffsetMismatch));

        byte[] centralDiskMismatch = base.clone();
        writeShort(centralDiskMismatch, entry.centralOffset() + 34, 1);
        assertUnsafe(() -> guard.validate(centralDiskMismatch));

        byte[] sizeMismatch = base.clone();
        writeInt(sizeMismatch, entry.centralOffset() + 24, entry.size() + 1);
        int sizeDescriptor = entry.dataOffset() + Math.toIntExact(entry.compressedSize());
        writeInt(sizeMismatch, sizeDescriptor + 12, entry.size() + 1);
        assertUnsafe(() -> guard.validate(sizeMismatch));

        byte[] stored = storedZip("stored.txt", "stored-body".getBytes(StandardCharsets.UTF_8));
        CentralEntry storedEntry = centralEntries(stored).getFirst();
        stored[storedEntry.localOffset() + 14] ^= 1;
        assertUnsafe(() -> guard.validate(stored));
    }

    @Test
    void rejectsCorruptPayloadCrcAndOverlappingLocalRecords() {
        byte[] corruptPayload = zip(List.of(
                new EntrySpec("safe.txt", 8_192, index -> index & 0xff)));
        CentralEntry first = centralEntries(corruptPayload).getFirst();
        corruptPayload[first.dataOffset() + Math.toIntExact(first.compressedSize() / 2)] ^= 1;
        assertUnsafe(() -> guard.validate(corruptPayload));

        byte[] overlapping = zip(List.of(
                new EntrySpec("first.txt", 8, index -> index),
                new EntrySpec("second.txt", 8, index -> index + 1)));
        List<CentralEntry> entries = centralEntries(overlapping);
        writeInt(
                overlapping,
                entries.get(1).centralOffset() + 42,
                entries.getFirst().localOffset());
        assertUnsafe(() -> guard.validate(overlapping));
    }

    @Test
    void interruptionAbortsActualInflationWithStableTimeoutCode() {
        byte[] archive = zip(List.of(
                new EntrySpec("safe.txt", 8_192, index -> index & 0xff)));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> guard.validate(archive))
                    .isInstanceOfSatisfying(AttachmentException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT))
                    .hasMessageNotContaining("safe.txt");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsUnreferencedLocalRecordBeforeTheFirstReferencedEntry() {
        byte[] archive = zip(List.of(
                new EntrySpec("word/document.xml", 128, index -> index & 0xff)));
        byte[] hidden = localStoredRecord(
                "hidden.xml",
                "hidden-before".getBytes(StandardCharsets.UTF_8));

        assertUnsafe(() -> guard.validate(insertUnreferencedLocalRecord(
                archive,
                0,
                hidden)));
    }

    @Test
    void rejectsUnreferencedDangerousLocalRecordBetweenReferencedEntries() {
        byte[] archive = zip(List.of(
                new EntrySpec("word/document.xml", 128, index -> index & 0xff),
                new EntrySpec("word/styles.xml", 128, index -> (index + 1) & 0xff)));
        int between = centralEntries(archive).get(1).localOffset();
        byte[] hidden = localStoredRecord(
                "../hidden.xml",
                "hidden-between".getBytes(StandardCharsets.UTF_8));

        assertUnsafe(() -> guard.validate(insertUnreferencedLocalRecord(
                archive,
                between,
                hidden)));
    }

    @Test
    void rejectsUnreferencedLocalRecordInTheTailBeforeCentralDirectory() {
        byte[] archive = zip(List.of(
                new EntrySpec("word/document.xml", 128, index -> index & 0xff)));
        int eocd = findSignature(archive, EOCD_SIGNATURE, 0);
        int directoryOffset = Math.toIntExact(unsignedInt(archive, eocd + 16));
        byte[] hidden = localStoredRecord(
                "tail.xml",
                "hidden-tail".getBytes(StandardCharsets.UTF_8));

        assertUnsafe(() -> guard.validate(insertUnreferencedLocalRecord(
                archive,
                directoryOffset,
                hidden)));
    }

    private static byte[] zip(List<EntrySpec> entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (EntrySpec entry : entries) {
                    zip.putNextEntry(new ZipEntry(entry.name()));
                    int offset = 0;
                    while (offset < entry.size()) {
                        int chunk = Math.min(buffer.length, entry.size() - offset);
                        for (int index = 0; index < chunk; index++) {
                            buffer[index] = (byte) entry.byteAt().byteAt(offset + index);
                        }
                        zip.write(buffer, 0, chunk);
                        offset += chunk;
                    }
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] storedZip(String name, byte[] data) {
        try {
            CRC32 crc = new CRC32();
            crc.update(data);
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            entry.setCompressedSize(data.length);
            entry.setCrc(crc.getValue());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] localStoredRecord(String name, byte[] data) {
        byte[] encodedName = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeInt(output, LOCAL_SIGNATURE);
        writeShort(output, 20);
        writeShort(output, 1 << 11);
        writeShort(output, ZipEntry.STORED);
        writeShort(output, 0);
        writeShort(output, 0);
        writeInt(output, crc.getValue());
        writeInt(output, data.length);
        writeInt(output, data.length);
        writeShort(output, encodedName.length);
        writeShort(output, 0);
        output.writeBytes(encodedName);
        output.writeBytes(data);
        return output.toByteArray();
    }

    private static byte[] insertUnreferencedLocalRecord(
            byte[] archive,
            int insertionOffset,
            byte[] hidden
    ) {
        List<CentralEntry> originalEntries = centralEntries(archive);
        int originalEocd = findSignature(archive, EOCD_SIGNATURE, 0);
        int originalDirectoryOffset =
                Math.toIntExact(unsignedInt(archive, originalEocd + 16));
        assertThat(insertionOffset).isBetween(0, originalDirectoryOffset);

        byte[] modified = new byte[archive.length + hidden.length];
        System.arraycopy(archive, 0, modified, 0, insertionOffset);
        System.arraycopy(hidden, 0, modified, insertionOffset, hidden.length);
        System.arraycopy(
                archive,
                insertionOffset,
                modified,
                insertionOffset + hidden.length,
                archive.length - insertionOffset);

        for (CentralEntry entry : originalEntries) {
            int newCentralOffset = entry.centralOffset() + hidden.length;
            long newLocalOffset = entry.localOffset() >= insertionOffset
                    ? entry.localOffset() + (long) hidden.length
                    : entry.localOffset();
            writeInt(modified, newCentralOffset + 42, newLocalOffset);
        }
        int newEocd = originalEocd + hidden.length;
        writeInt(
                modified,
                newEocd + 16,
                originalDirectoryOffset + (long) hidden.length);
        return modified;
    }

    private static List<CentralEntry> centralEntries(byte[] zip) {
        int eocd = findSignature(zip, EOCD_SIGNATURE, 0);
        int count = unsignedShort(zip, eocd + 10);
        int cursor = Math.toIntExact(unsignedInt(zip, eocd + 16));
        List<CentralEntry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            assertThat(unsignedInt(zip, cursor)).isEqualTo(Integer.toUnsignedLong(CENTRAL_SIGNATURE));
            int flags = unsignedShort(zip, cursor + 8);
            long compressed = unsignedInt(zip, cursor + 20);
            long size = unsignedInt(zip, cursor + 24);
            int nameLength = unsignedShort(zip, cursor + 28);
            int extraLength = unsignedShort(zip, cursor + 30);
            int commentLength = unsignedShort(zip, cursor + 32);
            int localOffset = Math.toIntExact(unsignedInt(zip, cursor + 42));
            int localNameLength = unsignedShort(zip, localOffset + 26);
            int localExtraLength = unsignedShort(zip, localOffset + 28);
            int dataOffset = localOffset + 30 + localNameLength + localExtraLength;
            entries.add(new CentralEntry(
                    cursor, localOffset, dataOffset, flags, compressed, size));
            cursor += 46 + nameLength + extraLength + commentLength;
        }
        return entries;
    }

    private static int findSignature(byte[] bytes, int signature, int start) {
        for (int index = start; index <= bytes.length - 4; index++) {
            if (unsignedInt(bytes, index) == Integer.toUnsignedLong(signature)) {
                return index;
            }
        }
        throw new AssertionError("signature not found");
    }

    private static void replaceAscii(byte[] bytes, String before, String after) {
        byte[] needle = before.getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = after.getBytes(StandardCharsets.US_ASCII);
        assertThat(replacement).hasSameSizeAs(needle);
        int replaced = 0;
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            if (Arrays.equals(
                    bytes, index, index + needle.length,
                    needle, 0, needle.length)) {
                System.arraycopy(replacement, 0, bytes, index, replacement.length);
                replaced++;
            }
        }
        assertThat(replaced).isEqualTo(2);
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(bytes[offset])
                        | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                        | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                        | Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static void writeShort(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void writeInt(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
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
                        assertThat(failure.code())
                                .isEqualTo(AttachmentErrorCode.ATTACHMENT_ARCHIVE_UNSAFE))
                .hasMessageNotContaining("\\")
                .hasMessageNotContaining("/")
                .hasMessageNotContaining("safe.txt");
    }

    private record EntrySpec(String name, int size, ByteGenerator byteAt) {
    }

    @FunctionalInterface
    private interface ByteGenerator {
        int byteAt(int index);
    }

    private record CentralEntry(
            int centralOffset,
            int localOffset,
            int dataOffset,
            int flags,
            long compressedSize,
            long size
    ) {
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
