package com.wzx.babiq.server.business.upload;

import com.wzx.babiq.server.settings.SecretStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Server-only storage for OA file ids. SQLite receives only the returned SecretStore reference. */
public final class BusinessAttachmentFileIdStore {
    private static final String NAMESPACE = "business.attachment.fileIds";
    private static final String DECLARATION_NAMESPACE = "business.attachment.declarations";
    private final SecretStore secrets;

    public BusinessAttachmentFileIdStore(SecretStore secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    public String save(String batchId, List<char[]> fileIds) {
        requireBatchId(batchId);
        char[] envelope = encode(fileIds);
        try {
            return secrets.saveChars(NAMESPACE + "." + batchId, envelope);
        } finally {
            Arrays.fill(envelope, '\0');
        }
    }

    public StoredFileIds load(String secretRef) {
        char[] envelope = secrets.requireChars(secretRef);
        try {
            return new StoredFileIds(decode(envelope));
        } finally {
            Arrays.fill(envelope, '\0');
        }
    }

    public void delete(String secretRef) {
        if (secretRef != null && !secretRef.isBlank()) secrets.delete(secretRef);
    }

    public String saveDeclarations(String batchId, char[] value) {
        requireBatchId(batchId);
        if (value == null || value.length == 0 || value.length > 100_000) {
            throw new IllegalArgumentException("invalid attachment declarations");
        }
        try {
            return secrets.saveChars(DECLARATION_NAMESPACE + "." + batchId, value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    public char[] loadDeclarations(String secretRef) {
        return secrets.requireChars(secretRef);
    }

    private static char[] encode(List<char[]> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || fileIds.size() > BusinessAttachmentTicketService.MAX_FILE_COUNT) {
            throw new IllegalArgumentException("invalid remote file ids");
        }
        int length = 0;
        for (char[] id : fileIds) {
            validate(id);
            length = Math.addExact(length, 4 + id.length);
        }
        char[] result = new char[length];
        int offset = 0;
        for (char[] id : fileIds) {
            int size = id.length;
            result[offset++] = (char) ((size >>> 24) & 0xffff);
            result[offset++] = (char) ((size >>> 16) & 0xffff);
            result[offset++] = (char) ((size >>> 8) & 0xffff);
            result[offset++] = (char) (size & 0xffff);
            System.arraycopy(id, 0, result, offset, id.length);
            offset += id.length;
        }
        return result;
    }

    private static List<char[]> decode(char[] envelope) {
        List<char[]> ids = new ArrayList<>();
        int offset = 0;
        try {
            while (offset < envelope.length) {
                if (envelope.length - offset < 4) throw new IllegalArgumentException("invalid file id envelope");
                int size = ((envelope[offset++] & 0xffff) << 24)
                        | ((envelope[offset++] & 0xffff) << 16)
                        | ((envelope[offset++] & 0xffff) << 8)
                        | (envelope[offset++] & 0xffff);
                if (size <= 0 || size > 512 || size > envelope.length - offset) {
                    throw new IllegalArgumentException("invalid file id envelope");
                }
                char[] id = Arrays.copyOfRange(envelope, offset, offset + size);
                validate(id);
                ids.add(id);
                offset += size;
            }
            if (ids.isEmpty() || ids.size() > BusinessAttachmentTicketService.MAX_FILE_COUNT) {
                throw new IllegalArgumentException("invalid file id envelope");
            }
            return ids;
        } catch (RuntimeException failure) {
            ids.forEach(value -> Arrays.fill(value, '\0'));
            throw failure;
        }
    }

    private static void validate(char[] id) {
        if (id == null || id.length == 0 || id.length > 512) throw new IllegalArgumentException("invalid remote file id");
        for (char current : id) {
            if (Character.isISOControl(current)) throw new IllegalArgumentException("invalid remote file id");
        }
    }

    private static void requireBatchId(String batchId) {
        if (batchId == null || batchId.isBlank()) throw new IllegalArgumentException("batchId must not be blank");
    }

    public static final class StoredFileIds implements AutoCloseable {
        private final List<char[]> values;

        private StoredFileIds(List<char[]> values) { this.values = List.copyOf(values); }

        public List<char[]> values() { return values; }
        public int size() { return values.size(); }

        @Override public void close() {
            values.forEach(value -> Arrays.fill(value, '\0'));
        }

        @Override public String toString() {
            return "StoredFileIds(fileCount=" + values.size() + ", values=[REDACTED])";
        }
    }
}
