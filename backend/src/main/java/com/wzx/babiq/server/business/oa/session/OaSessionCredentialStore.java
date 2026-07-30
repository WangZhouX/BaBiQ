package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.settings.SecretStore;
import com.wzx.babiq.server.settings.SecretStoreException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将 OA access/refresh token 封装后交给后端 SecretStore。
 *
 * <p>SecretStore 的边界使用字符数组。这里的 envelope 只保存版本、长度和 UTF-8
 * 字节的十六进制表示，因此 token 明文和 Base64 token 都不会出现在 SecretStore
 * 的 envelope 文本中；真正的机密性仍由 JCEKS SecretStore 提供。</p>
 */
public final class OaSessionCredentialStore {
    private static final String BUSINESS_OA_NAMESPACE_PREFIX = "business.oa.";
    private static final String BUSINESS_OA_KEYSTORE_REF_PREFIX = "keystore://" + BUSINESS_OA_NAMESPACE_PREFIX;
    private static final char[] MAGIC = "OA-CRED1".toCharArray();
    private static final int HEX_HEADER_CHARS = 8;
    private static final int MAX_TOKEN_BYTES = 1024 * 1024;
    private static final int MAX_ENVELOPE_CHARS = MAGIC.length + (12 + MAX_TOKEN_BYTES * 2) * 2;
    private final SecretStore secretStore;
    private final Set<String> allocatedRefs = ConcurrentHashMap.newKeySet();

    public OaSessionCredentialStore(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    /** 预分配 OA 专属 SecretStore 引用，供数据库先持久化 cleanup tombstone。 */
    public String allocateRef(String authSessionId) {
        requireAuthSessionId(authSessionId);
        String secretRef;
        try {
            secretRef = secretStore.allocateRef(BUSINESS_OA_NAMESPACE_PREFIX + authSessionId);
        } catch (RuntimeException ignored) {
            throw allocationFailure();
        }
        rememberAllocated(secretRef);
        return secretRef;
    }

    /** 将版本化 token envelope 写入已预分配的 OA 引用；绝不覆盖已有 entry。 */
    public void saveAtRef(String secretRef, int version, char[] accessToken, char[] refreshToken) {
        requireWritableBusinessOaRef(secretRef);
        requireMaterial(accessToken, "accessToken");
        requireMaterial(refreshToken, "refreshToken");
        char[] envelope = null;
        try {
            envelope = encode(version, accessToken, refreshToken);
            secretStore.saveCharsAtRef(secretRef, envelope);
        } catch (RuntimeException exception) {
            throw sanitizedWriteFailure(exception);
        } finally {
            wipe(envelope);
        }
    }

    /** 稳定枚举 OA namespace 下的引用，并对底层返回值再次做边界过滤。 */
    public List<String> listBusinessOaRefs() {
        List<String> refs;
        try {
            refs = secretStore.listRefs(BUSINESS_OA_NAMESPACE_PREFIX);
        } catch (RuntimeException ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_LIST_FAILED", "枚举 OA 本地凭据引用失败");
        }
        if (refs == null) {
            throw new SecretStoreException(
                    "SECRET_STORE_LIST_FAILED", "枚举 OA 本地凭据引用失败");
        }
        return refs.stream()
                .filter(this::isOwnedBusinessOaRef)
                .distinct()
                .sorted()
                .toList();
    }

    /** 读取 token envelope；返回的 material 必须由调用方 try-with-resources 关闭。 */
    public CredentialMaterial load(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) return null;
        requireWritableBusinessOaRef(secretRef);
        Optional<char[]> stored;
        try {
            stored = secretStore.loadChars(secretRef);
        } catch (RuntimeException ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_READ_FAILED", "读取 OA 本地凭据失败");
        }
        if (stored.isEmpty()) return null;
        char[] envelope = stored.get();
        try {
            return decode(envelope);
        } catch (RuntimeException ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_READ_FAILED", "读取 OA 本地凭据失败");
        } finally {
            wipe(envelope);
        }
    }

    public void delete(String secretRef) {
        if (secretRef != null && !secretRef.isBlank()) {
            requireWritableBusinessOaRef(secretRef);
            try {
                secretStore.delete(secretRef);
            } catch (RuntimeException ignored) {
                throw new SecretStoreException(
                        "SECRET_STORE_DELETE_FAILED", "删除 OA 本地凭据失败");
            }
            allocatedRefs.remove(secretRef);
        }
    }

    private void rememberAllocated(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            throw invalidReference();
        }
        if (isKeystoreRef(secretRef)) {
            if (!isDurableBusinessOaKeystoreRef(secretRef)) {
                throw invalidReference();
            }
            return;
        }
        if (isNoopRef(secretRef)) {
            throw invalidReference();
        }
        allocatedRefs.add(secretRef);
    }

    private void requireWritableBusinessOaRef(String secretRef) {
        if (!isOwnedBusinessOaRef(secretRef)) {
            throw invalidReference();
        }
    }

    private boolean isOwnedBusinessOaRef(String secretRef) {
        if (secretRef == null) return false;
        if (isKeystoreRef(secretRef)) return isDurableBusinessOaKeystoreRef(secretRef);
        return !isNoopRef(secretRef) && allocatedRefs.contains(secretRef);
    }

    private static boolean isKeystoreRef(String secretRef) {
        return secretRef.regionMatches(true, 0, "keystore://", 0, "keystore://".length());
    }

    private static boolean isNoopRef(String secretRef) {
        return secretRef.regionMatches(true, 0, "noop://", 0, "noop://".length());
    }

    private static boolean isDurableBusinessOaKeystoreRef(String secretRef) {
        return secretRef.startsWith(BUSINESS_OA_KEYSTORE_REF_PREFIX);
    }

    private static SecretStoreException invalidReference() {
        return new SecretStoreException(
                "SECRET_STORE_REFERENCE_INVALID", "OA SecretStore 引用格式无效");
    }

    private static SecretStoreException sanitizedWriteFailure(RuntimeException exception) {
        if (exception instanceof SecretStoreException secretStoreException
                && "SECRET_STORE_REFERENCE_EXISTS".equals(secretStoreException.resultCode())) {
            return new SecretStoreException(
                    "SECRET_STORE_REFERENCE_EXISTS", "SecretStore 引用已存在");
        }
        return new SecretStoreException(
                "SECRET_STORE_WRITE_FAILED", "写入 OA 本地凭据失败");
    }

    private static SecretStoreException allocationFailure() {
        return new SecretStoreException(
                "SECRET_STORE_REFERENCE_ALLOCATION_FAILED", "分配 OA 本地凭据引用失败");
    }

    private static void requireAuthSessionId(String authSessionId) {
        if (authSessionId == null || authSessionId.isBlank()) {
            throw new IllegalArgumentException("authSessionId must not be blank");
        }
    }

    private static char[] encode(int version, char[] accessToken, char[] refreshToken) {
        if (version < 0) throw new IllegalArgumentException("credential version must not be negative");
        byte[] access = null;
        byte[] refresh = null;
        byte[] payload = null;
        try {
            access = utf8(accessToken, "accessToken");
            refresh = utf8(refreshToken, "refreshToken");
            if (access.length > MAX_TOKEN_BYTES || refresh.length > MAX_TOKEN_BYTES) {
                throw new IllegalArgumentException("OA credential is too large");
            }
            payload = new byte[12 + access.length + refresh.length];
            ByteBuffer output = ByteBuffer.wrap(payload);
            output.putInt(version);
            output.putInt(access.length);
            output.put(access);
            output.putInt(refresh.length);
            output.put(refresh);
            char[] result = new char[MAGIC.length + payload.length * 2];
            System.arraycopy(MAGIC, 0, result, 0, MAGIC.length);
            int offset = MAGIC.length;
            for (byte value : payload) {
                int unsigned = value & 0xff;
                result[offset++] = hex(unsigned >>> 4);
                result[offset++] = hex(unsigned & 0x0f);
            }
            return result;
        } finally {
            wipe(payload);
            wipe(access);
            wipe(refresh);
        }
    }

    private static CredentialMaterial decode(char[] envelope) {
        if (envelope.length < MAGIC.length + HEX_HEADER_CHARS * 3
                || envelope.length > MAX_ENVELOPE_CHARS
                || !startsWith(envelope, MAGIC)) {
            throw new IllegalArgumentException("unsupported OA credential envelope");
        }
        int hexStart = MAGIC.length;
        int encodedBytes = envelope.length - hexStart;
        if ((encodedBytes & 1) != 0) throw new IllegalArgumentException("malformed OA credential envelope");
        byte[] payload = new byte[encodedBytes / 2];
        byte[] access = null;
        byte[] refresh = null;
        char[] accessChars = null;
        char[] refreshChars = null;
        try {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) ((hex(envelope[hexStart + i * 2]) << 4)
                        | hex(envelope[hexStart + i * 2 + 1]));
            }
            ByteBuffer input = ByteBuffer.wrap(payload);
            int version = input.getInt();
            if (version < 0) throw new IllegalArgumentException("invalid OA credential version");
            int accessLength = checkedLength(input, "access");
            access = new byte[accessLength];
            input.get(access);
            int refreshLength = checkedLength(input, "refresh");
            refresh = new byte[refreshLength];
            input.get(refresh);
            if (input.hasRemaining()) throw new IllegalArgumentException("trailing OA credential data");
            accessChars = decodeUtf8(access);
            refreshChars = decodeUtf8(refresh);
            CredentialMaterial material = new CredentialMaterial(version, accessChars, refreshChars);
            accessChars = null;
            refreshChars = null;
            return material;
        } catch (java.nio.BufferUnderflowException | java.nio.BufferOverflowException exception) {
            throw new IllegalArgumentException("malformed OA credential envelope", exception);
        } finally {
            wipe(accessChars);
            wipe(refreshChars);
            wipe(access);
            wipe(refresh);
            wipe(payload);
        }
    }

    private static int checkedLength(ByteBuffer input, String name) {
        if (input.remaining() < Integer.BYTES) throw new IllegalArgumentException("missing " + name + " length");
        int length = input.getInt();
        if (length < 0 || length > MAX_TOKEN_BYTES || length > input.remaining()) {
            throw new IllegalArgumentException("invalid " + name + " length");
        }
        return length;
    }

    private static char[] decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            try {
                char[] result = new char[chars.remaining()];
                chars.get(result);
                return result;
            } finally {
                if (chars.hasArray()) wipe(chars.array());
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid UTF-8 OA credential", exception);
        }
    }

    private static byte[] utf8(char[] value, String name) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            try {
                byte[] result = new byte[encoded.remaining()];
                encoded.get(result);
                return result;
            } finally {
                wipe(encoded.array());
            }
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(name + " contains invalid UTF-16", exception);
        }
    }

    private static boolean startsWith(char[] value, char[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static char hex(int value) {
        return "0123456789abcdef".charAt(value & 0x0f);
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        throw new IllegalArgumentException("malformed OA credential envelope");
    }

    private static void requireMaterial(char[] value, String name) {
        if (value == null || value.length == 0) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static void wipe(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    public static final class CredentialMaterial implements AutoCloseable {
        private final int version;
        private final char[] accessToken;
        private final char[] refreshToken;

        private CredentialMaterial(int version, char[] accessToken, char[] refreshToken) {
            this.version = version;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public int version() { return version; }
        public char[] accessToken() { return accessToken; }
        public char[] refreshToken() { return refreshToken; }

        @Override
        public void close() {
            wipe(accessToken);
            wipe(refreshToken);
        }
    }
}
