package com.wzx.babiq.server.attachment;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads only the ZIP central directory and rejects unsafe OOXML containers before parsing.
 */
@Component
public final class OoxmlArchiveGuard {

    static final int MAX_ENTRIES = 1_000;
    static final long MAX_DECLARED_TOTAL_BYTES = 100L * 1024 * 1024;
    static final long MAX_DECLARED_ENTRY_BYTES = 50L * 1024 * 1024;
    static final long MAX_COMPRESSION_RATIO = 100;

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int MIN_EOCD_BYTES = 22;
    private static final int MAX_ZIP_COMMENT_BYTES = 65_535;

    public void validate(byte[] archiveBytes) {
        if (archiveBytes == null || archiveBytes.length < MIN_EOCD_BYTES) {
            throw unsafe();
        }
        ByteBuffer bytes = ByteBuffer.wrap(archiveBytes).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = findEocd(bytes);
        int diskNumber = unsignedShort(bytes, eocd + 4);
        int centralDisk = unsignedShort(bytes, eocd + 6);
        int entriesOnDisk = unsignedShort(bytes, eocd + 8);
        int entryCount = unsignedShort(bytes, eocd + 10);
        long directorySize = unsignedInt(bytes, eocd + 12);
        long directoryOffset = unsignedInt(bytes, eocd + 16);
        int commentLength = unsignedShort(bytes, eocd + 20);

        if (diskNumber != 0
                || centralDisk != 0
                || entriesOnDisk != entryCount
                || entryCount > MAX_ENTRIES
                || entryCount == 0xffff
                || directorySize == 0xffff_ffffL
                || directoryOffset == 0xffff_ffffL
                || eocd + MIN_EOCD_BYTES + commentLength != archiveBytes.length
                || directoryOffset + directorySize != eocd
                || directoryOffset > Integer.MAX_VALUE
                || directorySize > Integer.MAX_VALUE) {
            throw unsafe();
        }

        int cursor = (int) directoryOffset;
        int directoryEnd = Math.toIntExact(directoryOffset + directorySize);
        List<EntryMetadata> entries = new ArrayList<>(Math.min(entryCount, MAX_ENTRIES + 1));
        for (int index = 0; index < entryCount; index++) {
            requireRange(bytes, cursor, 46);
            if (bytes.getInt(cursor) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw unsafe();
            }
            int flags = unsignedShort(bytes, cursor + 8);
            long compressedSize = unsignedInt(bytes, cursor + 20);
            long size = unsignedInt(bytes, cursor + 24);
            int nameLength = unsignedShort(bytes, cursor + 28);
            int extraLength = unsignedShort(bytes, cursor + 30);
            int entryCommentLength = unsignedShort(bytes, cursor + 32);
            if ((flags & 1) != 0
                    || compressedSize == 0xffff_ffffL
                    || size == 0xffff_ffffL) {
                throw unsafe();
            }
            int entryLength;
            try {
                entryLength = Math.addExact(46,
                        Math.addExact(nameLength, Math.addExact(extraLength, entryCommentLength)));
            } catch (ArithmeticException exception) {
                throw unsafe();
            }
            requireRange(bytes, cursor, entryLength);
            String name = new String(
                    archiveBytes,
                    cursor + 46,
                    nameLength,
                    (flags & (1 << 11)) != 0
                            ? StandardCharsets.UTF_8
                            : StandardCharsets.ISO_8859_1);
            entries.add(new EntryMetadata(name, size, compressedSize));
            cursor += entryLength;
        }
        if (cursor != directoryEnd) {
            throw unsafe();
        }
        validateEntries(entries);
    }

    void validateEntries(List<EntryMetadata> entries) {
        if (entries == null || entries.size() > MAX_ENTRIES) {
            throw unsafe();
        }
        long total = 0;
        for (EntryMetadata entry : entries) {
            if (entry == null
                    || entry.size() < 0
                    || entry.compressedSize() < 0
                    || entry.size() > MAX_DECLARED_ENTRY_BYTES
                    || entry.size() > 0 && entry.compressedSize() == 0
                    || compressionRatioExceeded(entry.size(), entry.compressedSize())) {
                throw unsafe();
            }
            try {
                total = Math.addExact(total, entry.size());
            } catch (ArithmeticException exception) {
                throw unsafe();
            }
            if (total > MAX_DECLARED_TOTAL_BYTES) {
                throw unsafe();
            }
        }
    }

    private static boolean compressionRatioExceeded(long size, long compressedSize) {
        if (size == 0) {
            return false;
        }
        return size / compressedSize > MAX_COMPRESSION_RATIO
                || size % compressedSize != 0
                && size / compressedSize == MAX_COMPRESSION_RATIO;
    }

    private static int findEocd(ByteBuffer bytes) {
        int lowerBound = Math.max(0, bytes.limit() - MIN_EOCD_BYTES - MAX_ZIP_COMMENT_BYTES);
        for (int cursor = bytes.limit() - MIN_EOCD_BYTES; cursor >= lowerBound; cursor--) {
            if (bytes.getInt(cursor) == EOCD_SIGNATURE) {
                return cursor;
            }
        }
        throw unsafe();
    }

    private static int unsignedShort(ByteBuffer bytes, int offset) {
        requireRange(bytes, offset, Short.BYTES);
        return Short.toUnsignedInt(bytes.getShort(offset));
    }

    private static long unsignedInt(ByteBuffer bytes, int offset) {
        requireRange(bytes, offset, Integer.BYTES);
        return Integer.toUnsignedLong(bytes.getInt(offset));
    }

    private static void requireRange(ByteBuffer bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.limit() - length) {
            throw unsafe();
        }
    }

    private static AttachmentException unsafe() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_ARCHIVE_UNSAFE,
                "Office 附件的压缩容器超过安全限制");
    }

    record EntryMetadata(String name, long size, long compressedSize) {
        @Override
        public String toString() {
            return "EntryMetadata[name=<redacted>, size=%d, compressedSize=%d]"
                    .formatted(size, compressedSize);
        }
    }
}
