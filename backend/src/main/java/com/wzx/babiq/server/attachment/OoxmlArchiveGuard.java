package com.wzx.babiq.server.attachment;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates OOXML ZIP structure and actually inflates every entry under strict limits.
 */
@Component
public final class OoxmlArchiveGuard {

    static final int MAX_ENTRIES = 1_000;
    static final long MAX_DECLARED_TOTAL_BYTES = 100L * 1024 * 1024;
    static final long MAX_DECLARED_ENTRY_BYTES = 50L * 1024 * 1024;
    static final long MAX_COMPRESSION_RATIO = 100;

    private static final int LOCAL_FILE_SIGNATURE = 0x04034b50;
    private static final int DATA_DESCRIPTOR_SIGNATURE = 0x08074b50;
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int ZIP64_EOCD_SIGNATURE = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int MIN_EOCD_BYTES = 22;
    private static final int MAX_ZIP_COMMENT_BYTES = 65_535;
    private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;
    private static final int UTF8_FLAG = 1 << 11;
    private static final int ZIP64_EXTRA_ID = 0x0001;

    public void validate(byte[] archiveBytes) {
        if (archiveBytes == null || archiveBytes.length < MIN_EOCD_BYTES) {
            throw unsafe();
        }
        ByteBuffer bytes = ByteBuffer.wrap(archiveBytes).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = findEocd(bytes);
        rejectZip64Locator(bytes, eocd);

        int diskNumber = unsignedShort(bytes, eocd + 4);
        int centralDisk = unsignedShort(bytes, eocd + 6);
        int entriesOnDisk = unsignedShort(bytes, eocd + 8);
        int entryCount = unsignedShort(bytes, eocd + 10);
        long directorySize = unsignedInt(bytes, eocd + 12);
        long directoryOffset = unsignedInt(bytes, eocd + 16);
        int commentLength = unsignedShort(bytes, eocd + 20);
        long directoryEnd = checkedAdd(directoryOffset, directorySize);

        if (diskNumber != 0
                || centralDisk != 0
                || entriesOnDisk != entryCount
                || entryCount == 0
                || entryCount > MAX_ENTRIES
                || entryCount == 0xffff
                || directorySize == 0xffff_ffffL
                || directoryOffset == 0xffff_ffffL
                || eocd + MIN_EOCD_BYTES + commentLength != archiveBytes.length
                || directoryEnd != eocd
                || directoryOffset > Integer.MAX_VALUE
                || directorySize > Integer.MAX_VALUE) {
            throw unsafe();
        }

        List<CentralEntry> entries = parseCentralDirectory(
                archiveBytes,
                bytes,
                Math.toIntExact(directoryOffset),
                Math.toIntExact(directoryEnd),
                entryCount);
        List<LocalEntry> localEntries = bindLocalEntries(
                archiveBytes, bytes, entries, Math.toIntExact(directoryOffset));
        rejectOverlaps(localEntries);
        verifyActualEntryData(archiveBytes, entries, localEntries);
    }

    private static List<CentralEntry> parseCentralDirectory(
            byte[] archiveBytes,
            ByteBuffer bytes,
            int directoryOffset,
            int directoryEnd,
            int entryCount
    ) {
        int cursor = directoryOffset;
        long declaredTotal = 0;
        Set<String> names = new HashSet<>();
        List<CentralEntry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            requireRange(bytes, cursor, 46);
            if (bytes.getInt(cursor) != CENTRAL_DIRECTORY_SIGNATURE) {
                throw unsafe();
            }
            int versionNeeded = unsignedShort(bytes, cursor + 6);
            int flags = unsignedShort(bytes, cursor + 8);
            int method = unsignedShort(bytes, cursor + 10);
            long crc = unsignedInt(bytes, cursor + 16);
            long compressedSize = unsignedInt(bytes, cursor + 20);
            long size = unsignedInt(bytes, cursor + 24);
            int nameLength = unsignedShort(bytes, cursor + 28);
            int extraLength = unsignedShort(bytes, cursor + 30);
            int entryCommentLength = unsignedShort(bytes, cursor + 32);
            int diskStart = unsignedShort(bytes, cursor + 34);
            long localOffset = unsignedInt(bytes, cursor + 42);
            int entryLength = checkedIntAdd(
                    46, nameLength, extraLength, entryCommentLength);
            requireRange(bytes, cursor, entryLength);
            if (versionNeeded >= 45
                    || diskStart != 0
                    || compressedSize == 0xffff_ffffL
                    || size == 0xffff_ffffL
                    || localOffset == 0xffff_ffffL
                    || localOffset > Integer.MAX_VALUE
                    || nameLength == 0
                    || !supportedFlags(flags, method)) {
                throw unsafe();
            }
            byte[] rawName = Arrays.copyOfRange(
                    archiveBytes, cursor + 46, cursor + 46 + nameLength);
            String name = decodeName(rawName, flags);
            validateName(name);
            if (!names.add(name)) {
                throw unsafe();
            }
            rejectZip64Extra(
                    bytes,
                    cursor + 46 + nameLength,
                    extraLength);
            validateDeclaredEntry(size, compressedSize);
            declaredTotal = checkedAdd(declaredTotal, size);
            if (declaredTotal > MAX_DECLARED_TOTAL_BYTES) {
                throw unsafe();
            }
            entries.add(new CentralEntry(
                    Math.toIntExact(localOffset),
                    flags,
                    method,
                    crc,
                    compressedSize,
                    size,
                    rawName));
            cursor += entryLength;
        }
        if (cursor != directoryEnd) {
            throw unsafe();
        }
        return entries;
    }

    private static List<LocalEntry> bindLocalEntries(
            byte[] archiveBytes,
            ByteBuffer bytes,
            List<CentralEntry> entries,
            int directoryOffset
    ) {
        List<LocalEntry> localEntries = new ArrayList<>(entries.size());
        for (CentralEntry entry : entries) {
            int localOffset = entry.localOffset();
            requireRange(bytes, localOffset, 30);
            if (bytes.getInt(localOffset) != LOCAL_FILE_SIGNATURE) {
                throw unsafe();
            }
            int versionNeeded = unsignedShort(bytes, localOffset + 4);
            int flags = unsignedShort(bytes, localOffset + 6);
            int method = unsignedShort(bytes, localOffset + 8);
            long crc = unsignedInt(bytes, localOffset + 14);
            long compressedSize = unsignedInt(bytes, localOffset + 18);
            long size = unsignedInt(bytes, localOffset + 22);
            int nameLength = unsignedShort(bytes, localOffset + 26);
            int extraLength = unsignedShort(bytes, localOffset + 28);
            int headerLength = checkedIntAdd(30, nameLength, extraLength);
            requireRange(bytes, localOffset, headerLength);
            byte[] rawName = Arrays.copyOfRange(
                    archiveBytes,
                    localOffset + 30,
                    localOffset + 30 + nameLength);
            if (versionNeeded >= 45
                    || flags != entry.flags()
                    || method != entry.method()
                    || !Arrays.equals(rawName, entry.rawName())) {
                throw unsafe();
            }
            rejectZip64Extra(
                    bytes,
                    localOffset + 30 + nameLength,
                    extraLength);
            boolean usesDescriptor = (flags & DATA_DESCRIPTOR_FLAG) != 0;
            if (usesDescriptor) {
                if (crc != 0 && crc != entry.crc()
                        || compressedSize != 0 && compressedSize != entry.compressedSize()
                        || size != 0 && size != entry.size()) {
                    throw unsafe();
                }
            } else if (crc != entry.crc()
                    || compressedSize != entry.compressedSize()
                    || size != entry.size()) {
                throw unsafe();
            }

            long dataOffsetLong = checkedAdd(localOffset, headerLength);
            long dataEndLong = checkedAdd(dataOffsetLong, entry.compressedSize());
            if (dataEndLong > directoryOffset || dataEndLong > archiveBytes.length) {
                throw unsafe();
            }
            int dataOffset = Math.toIntExact(dataOffsetLong);
            int dataEnd = Math.toIntExact(dataEndLong);
            int recordEnd = usesDescriptor
                    ? validateDescriptor(bytes, dataEnd, entry)
                    : dataEnd;
            if (recordEnd > directoryOffset) {
                throw unsafe();
            }
            localEntries.add(new LocalEntry(localOffset, dataOffset, recordEnd));
        }
        return localEntries;
    }

    private static int validateDescriptor(
            ByteBuffer bytes,
            int descriptorOffset,
            CentralEntry entry
    ) {
        requireRange(bytes, descriptorOffset, 12);
        int cursor = descriptorOffset;
        if (bytes.getInt(cursor) == DATA_DESCRIPTOR_SIGNATURE) {
            requireRange(bytes, cursor, 16);
            cursor += 4;
        }
        long crc = unsignedInt(bytes, cursor);
        long compressedSize = unsignedInt(bytes, cursor + 4);
        long size = unsignedInt(bytes, cursor + 8);
        if (crc != entry.crc()
                || compressedSize != entry.compressedSize()
                || size != entry.size()) {
            throw unsafe();
        }
        return cursor + 12;
    }

    private static void rejectOverlaps(List<LocalEntry> localEntries) {
        List<LocalEntry> ordered = new ArrayList<>(localEntries);
        ordered.sort(Comparator.comparingInt(LocalEntry::recordOffset));
        int previousEnd = -1;
        for (LocalEntry entry : ordered) {
            if (entry.recordOffset() < previousEnd
                    || entry.dataOffset() < entry.recordOffset()
                    || entry.recordEnd() < entry.dataOffset()) {
                throw unsafe();
            }
            previousEnd = entry.recordEnd();
        }
    }

    private static void verifyActualEntryData(
            byte[] archiveBytes,
            List<CentralEntry> entries,
            List<LocalEntry> localEntries
    ) {
        List<OoxmlEntryDataVerifier.Entry> verified = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CentralEntry entry = entries.get(index);
            LocalEntry local = localEntries.get(index);
            verified.add(new OoxmlEntryDataVerifier.Entry(
                    local.dataOffset(),
                    entry.method(),
                    entry.crc(),
                    entry.compressedSize(),
                    entry.size()));
        }
        new OoxmlEntryDataVerifier(
                MAX_DECLARED_ENTRY_BYTES,
                MAX_DECLARED_TOTAL_BYTES,
                MAX_COMPRESSION_RATIO)
                .verify(archiveBytes, verified);
    }

    private static void validateDeclaredEntry(long size, long compressedSize) {
        if (size > MAX_DECLARED_ENTRY_BYTES
                || size > 0 && compressedSize == 0
                || compressionRatioExceeded(size, compressedSize)) {
            throw unsafe();
        }
    }

    private static boolean compressionRatioExceeded(long size, long compressedSize) {
        if (size == 0) {
            return false;
        }
        if (compressedSize == 0) {
            return true;
        }
        return size / compressedSize > MAX_COMPRESSION_RATIO
                || size % compressedSize != 0
                && size / compressedSize == MAX_COMPRESSION_RATIO;
    }

    private static boolean supportedFlags(int flags, int method) {
        if (method != ZipMethod.STORED.code && method != ZipMethod.DEFLATED.code) {
            return false;
        }
        int allowed = DATA_DESCRIPTOR_FLAG | UTF8_FLAG;
        if (method == ZipMethod.DEFLATED.code) {
            allowed |= 0x0006;
        }
        return (flags & ~allowed) == 0;
    }

    private static String decodeName(byte[] rawName, int flags) {
        if ((flags & UTF8_FLAG) == 0) {
            return new String(rawName, StandardCharsets.ISO_8859_1);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw unsafe();
        }
    }

    private static void validateName(String name) {
        if (name.isEmpty()
                || name.indexOf('\0') >= 0
                || name.startsWith("/")
                || name.startsWith("\\")
                || name.indexOf('\\') >= 0
                || name.length() >= 2
                && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':') {
            throw unsafe();
        }
        String logicalName = name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        if (logicalName.isEmpty()) {
            throw unsafe();
        }
        for (String segment : logicalName.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw unsafe();
            }
        }
    }

    private static void rejectZip64Extra(ByteBuffer bytes, int offset, int length) {
        int cursor = offset;
        int end = checkedIntAdd(offset, length);
        while (cursor < end) {
            requireRange(bytes, cursor, 4);
            int headerId = unsignedShort(bytes, cursor);
            int dataSize = unsignedShort(bytes, cursor + 2);
            cursor = checkedIntAdd(cursor, 4);
            requireRange(bytes, cursor, dataSize);
            if (headerId == ZIP64_EXTRA_ID) {
                throw unsafe();
            }
            cursor = checkedIntAdd(cursor, dataSize);
        }
        if (cursor != end) {
            throw unsafe();
        }
    }

    private static void rejectZip64Locator(ByteBuffer bytes, int eocd) {
        if (eocd >= 20 && bytes.getInt(eocd - 20) == ZIP64_LOCATOR_SIGNATURE
                || eocd >= 56 && bytes.getInt(eocd - 56) == ZIP64_EOCD_SIGNATURE) {
            throw unsafe();
        }
    }

    private static int findEocd(ByteBuffer bytes) {
        int lowerBound = Math.max(0, bytes.limit() - MIN_EOCD_BYTES - MAX_ZIP_COMMENT_BYTES);
        for (int cursor = bytes.limit() - MIN_EOCD_BYTES; cursor >= lowerBound; cursor--) {
            if (bytes.getInt(cursor) == EOCD_SIGNATURE
                    && cursor + MIN_EOCD_BYTES <= bytes.limit()
                    && cursor + MIN_EOCD_BYTES + unsignedShort(bytes, cursor + 20)
                    == bytes.limit()) {
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

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw unsafe();
        }
    }

    private static int checkedIntAdd(int... values) {
        int total = 0;
        try {
            for (int value : values) {
                total = Math.addExact(total, value);
            }
            return total;
        } catch (ArithmeticException exception) {
            throw unsafe();
        }
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

    private enum ZipMethod {
        STORED(0),
        DEFLATED(8);

        private final int code;

        ZipMethod(int code) {
            this.code = code;
        }
    }

    private record CentralEntry(
            int localOffset,
            int flags,
            int method,
            long crc,
            long compressedSize,
            long size,
            byte[] rawName
    ) {
        private CentralEntry {
            rawName = rawName.clone();
        }

        @Override
        public byte[] rawName() {
            return rawName.clone();
        }
    }

    private record LocalEntry(int recordOffset, int dataOffset, int recordEnd) {
    }
}
