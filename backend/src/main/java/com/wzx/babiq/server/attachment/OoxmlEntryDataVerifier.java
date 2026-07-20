package com.wzx.babiq.server.attachment;

import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Streams the verified compressed spans and enforces limits against actual output.
 */
final class OoxmlEntryDataVerifier {

    private static final int STORED = 0;
    private static final int INFLATE_BUFFER_BYTES = 64 * 1024;

    private final long maximumEntryBytes;
    private final long maximumTotalBytes;
    private final long maximumCompressionRatio;

    OoxmlEntryDataVerifier(
            long maximumEntryBytes,
            long maximumTotalBytes,
            long maximumCompressionRatio
    ) {
        this.maximumEntryBytes = maximumEntryBytes;
        this.maximumTotalBytes = maximumTotalBytes;
        this.maximumCompressionRatio = maximumCompressionRatio;
    }

    void verify(byte[] archiveBytes, List<Entry> entries) {
        byte[] output = new byte[INFLATE_BUFFER_BYTES];
        long actualTotal = 0;
        for (Entry entry : entries) {
            requireNotInterrupted();
            CRC32 crc = new CRC32();
            long maximumActual = Math.min(
                    maximumEntryBytes,
                    maximumTotalBytes - actualTotal);
            maximumActual = Math.min(
                    maximumActual,
                    saturatedMultiply(entry.compressedSize(), maximumCompressionRatio));
            long actualSize = entry.method() == STORED
                    ? verifyStored(archiveBytes, entry, crc, maximumActual)
                    : verifyDeflated(archiveBytes, entry, output, crc, maximumActual);
            if (actualSize != entry.size() || crc.getValue() != entry.crc()) {
                throw unsafe();
            }
            actualTotal = checkedAdd(actualTotal, actualSize);
            if (actualTotal > maximumTotalBytes) {
                throw unsafe();
            }
        }
    }

    private long verifyStored(
            byte[] archiveBytes,
            Entry entry,
            CRC32 crc,
            long maximumActual
    ) {
        if (entry.compressedSize() != entry.size()) {
            throw unsafe();
        }
        long actual = 0;
        int cursor = entry.dataOffset();
        int remaining = Math.toIntExact(entry.compressedSize());
        while (remaining > 0) {
            requireNotInterrupted();
            int chunk = Math.min(INFLATE_BUFFER_BYTES, remaining);
            crc.update(archiveBytes, cursor, chunk);
            actual += chunk;
            requireWithinLimit(actual, maximumActual);
            cursor += chunk;
            remaining -= chunk;
        }
        return actual;
    }

    private long verifyDeflated(
            byte[] archiveBytes,
            Entry entry,
            byte[] output,
            CRC32 crc,
            long maximumActual
    ) {
        Inflater inflater = new Inflater(true);
        int compressedLength = Math.toIntExact(entry.compressedSize());
        try {
            inflater.setInput(archiveBytes, entry.dataOffset(), compressedLength);
            long actual = 0;
            while (!inflater.finished()) {
                requireNotInterrupted();
                int inflated = inflater.inflate(output);
                if (inflated == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    throw unsafe();
                }
                crc.update(output, 0, inflated);
                actual = checkedAdd(actual, inflated);
                requireWithinLimit(actual, maximumActual);
            }
            if (inflater.getBytesRead() != compressedLength) {
                throw unsafe();
            }
            return actual;
        } catch (DataFormatException exception) {
            throw unsafe();
        } finally {
            inflater.end();
        }
    }

    private static void requireWithinLimit(long actual, long maximum) {
        if (actual > maximum) {
            throw unsafe();
        }
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new AttachmentException(
                    AttachmentErrorCode.ATTACHMENT_PARSE_TIMEOUT,
                    "附件解析超时");
        }
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw unsafe();
        }
    }

    private static long saturatedMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static AttachmentException unsafe() {
        return new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_ARCHIVE_UNSAFE,
                "Office 附件的压缩容器超过安全限制");
    }

    record Entry(
            int dataOffset,
            int method,
            long crc,
            long compressedSize,
            long size
    ) {
    }
}
