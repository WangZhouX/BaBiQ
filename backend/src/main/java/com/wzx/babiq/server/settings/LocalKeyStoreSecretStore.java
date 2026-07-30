package com.wzx.babiq.server.settings;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JDK KeyStore 的本地密钥存储实现。
 *
 * <p>JDK 官方 KeyStore 支持 `SecretKeyEntry`，适合把本地 API Key 从 SQLite 里隔离出去。
 * 本实现保存的是单机本地文件，不解决“同一台机器上的恶意用户”问题；它的安全边界是：
 * 数据库、日志和 JSON-RPC 响应都只能看到 `keystore://alias`，不会保存或回显明文 API Key。</p>
 */
@Primary
@Component
public class LocalKeyStoreSecretStore implements SecretStore {

    /** JCEKS 对 SecretKeyEntry 支持稳定，适合保存对称密钥形式的本地 API Key。 */
    private static final String STORE_TYPE = "JCEKS";
    /** API Key 不是加密算法密钥，这里用 RAW 表示按原始字节保存和恢复。 */
    private static final String SECRET_ALGORITHM = "RAW";
    /** 对外暴露的引用前缀，数据库只保存此前缀加 alias。 */
    private static final String REF_PREFIX = "keystore://";
    private static final String DEFAULT_PASSWORD = "babiq-local-secret-store";

    /** KeyStore 文件路径，默认位于用户目录下的 `.babiq`。 */
    private final Path storePath;
    /** KeyStore 文件和条目共用的本地保护口令。 */
    private final char[] password;
    /** 完整临时文件写入后负责替换正式 KeyStore 文件的提交器。 */
    private final StoreFileCommitter storeFileCommitter;
    /** 为每次 entry 操作创建独立、可销毁的口令保护对象。 */
    private final PasswordProtectionFactory passwordProtectionFactory;

    /** 同目录临时 KeyStore 文件的最终提交边界。 */
    @FunctionalInterface
    interface StoreFileCommitter {
        /**
         * 原子替换目标文件；抛出异常时目标文件必须保持调用前内容不变。
         */
        void commit(Path temporaryPath, Path targetPath) throws IOException;
    }

    @FunctionalInterface
    interface PasswordProtectionFactory {
        KeyStore.PasswordProtection create(char[] password);
    }

    @FunctionalInterface
    private interface PasswordProtectionOperation<T> {
        T apply(KeyStore.PasswordProtection protection) throws Exception;
    }

    /**
     * Spring 生产构造器。
     *
     * @param storePath 配置项 `babiq.secrets.keystore-path`
     * @param password 配置项 `babiq.secrets.keystore-password`
     */
    @Autowired
    public LocalKeyStoreSecretStore(
            @Value("${babiq.secrets.keystore-path:${user.home}/.babiq/babiq-secrets.jceks}") String storePath,
            @Value("${babiq.secrets.keystore-password:}") String password,
            @Value("${babiq.business.enabled:false}") boolean businessProfile) {
        this(Path.of(storePath), effectivePassword(password, businessProfile), businessProfile);
    }

    /**
     * 测试和手动构造器。
     *
     * @param storePath KeyStore 文件路径
     * @param password 本地保护口令
     */
    public LocalKeyStoreSecretStore(Path storePath, char[] password) {
        this(storePath, password, false, LocalKeyStoreSecretStore::atomicReplace);
    }

    /**
     * 允许包内测试注入目标文件替换失败，不向生产调用方暴露额外配置面。
     */
    LocalKeyStoreSecretStore(Path storePath, char[] password, StoreFileCommitter storeFileCommitter) {
        this(storePath, password, false, storeFileCommitter);
    }

    LocalKeyStoreSecretStore(
            Path storePath,
            char[] password,
            PasswordProtectionFactory passwordProtectionFactory) {
        this(storePath, password, false, LocalKeyStoreSecretStore::atomicReplace, passwordProtectionFactory);
    }

    LocalKeyStoreSecretStore(
            Path storePath,
            char[] password,
            StoreFileCommitter storeFileCommitter,
            PasswordProtectionFactory passwordProtectionFactory) {
        this(storePath, password, false, storeFileCommitter, passwordProtectionFactory);
    }

    private LocalKeyStoreSecretStore(Path storePath, char[] password, boolean businessProfile) {
        this(storePath, password, businessProfile, LocalKeyStoreSecretStore::atomicReplace);
    }

    private LocalKeyStoreSecretStore(Path storePath, char[] password, boolean businessProfile,
                                     StoreFileCommitter storeFileCommitter) {
        this(storePath, password, businessProfile, storeFileCommitter, KeyStore.PasswordProtection::new);
    }

    private LocalKeyStoreSecretStore(
            Path storePath,
            char[] password,
            boolean businessProfile,
            StoreFileCommitter storeFileCommitter,
            PasswordProtectionFactory passwordProtectionFactory) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("KeyStore password 不能为空");
        }
        if (businessProfile && isDefaultPassword(password)) {
            throw new IllegalArgumentException("business profile 必须配置非默认 KeyStore password");
        }
        this.storePath = storePath;
        this.password = password.clone();
        this.storeFileCommitter = storeFileCommitter;
        if (passwordProtectionFactory == null) {
            throw new IllegalArgumentException("PasswordProtectionFactory 不能为空");
        }
        this.passwordProtectionFactory = passwordProtectionFactory;
    }

    /** 为 business profile 提供显式 fail-closed 的 KeyStore 构造入口。 */
    public static LocalKeyStoreSecretStore forBusinessProfile(Path storePath, char[] password) {
        return new LocalKeyStoreSecretStore(storePath, password, true);
    }

    private static char[] effectivePassword(String configured, boolean businessProfile) {
        char[] value = configured == null ? new char[0] : configured.toCharArray();
        if (businessProfile && (value.length == 0 || isDefaultPassword(value))) {
            java.util.Arrays.fill(value, '\0');
            throw new IllegalArgumentException("business profile 必须配置非默认 KeyStore password");
        }
        return value.length == 0 ? DEFAULT_PASSWORD.toCharArray() : value;
    }

    private static boolean isDefaultPassword(char[] value) {
        return java.util.Arrays.equals(value, DEFAULT_PASSWORD.toCharArray());
    }

    private static void atomicReplace(Path temporaryPath, Path targetPath) throws IOException {
        Files.move(temporaryPath, targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 保存明文密钥并返回可持久化引用。
     *
     * @param namespace 密钥命名空间，用于生成可读 alias
     * @param secretPlainText 明文 API Key，只在本方法内短暂存在
     * @return `keystore://alias`
     */
    @Override
    public synchronized String save(String namespace, String secretPlainText) {
        if (secretPlainText == null || secretPlainText.isBlank()) {
            throw new IllegalArgumentException("secretPlainText 不能为空");
        }
        char[] chars = secretPlainText.toCharArray();
        try {
            return saveChars(namespace, chars);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    @Override
    public synchronized String saveChars(String namespace, char[] secretChars) {
        if (secretChars == null || secretChars.length == 0) {
            throw new IllegalArgumentException("secretChars 不能为空");
        }
        String secretRef = allocateRef(namespace);
        saveCharsAtRef(secretRef, secretChars);
        return secretRef;
    }

    @Override
    public String allocateRef(String namespace) {
        String canonicalNamespace = sanitize(namespace).toLowerCase(Locale.ROOT);
        return REF_PREFIX + canonicalNamespace + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public synchronized void saveCharsAtRef(String secretRef, char[] secretChars) {
        if (secretChars == null || secretChars.length == 0) {
            throw new IllegalArgumentException("secretChars 不能为空");
        }
        String alias = aliasFrom(secretRef);
        if (alias == null || !alias.equals(alias.toLowerCase(Locale.ROOT))) {
            throw new SecretStoreException(
                    "SECRET_STORE_REFERENCE_INVALID", "SecretStore 引用格式无效");
        }
        writeSecret(alias, secretChars);
    }

    @Override
    public synchronized List<String> listRefs(String namespacePrefix) {
        if (namespacePrefix == null || namespacePrefix.isBlank()) {
            throw new IllegalArgumentException("namespacePrefix 不能为空");
        }
        String sanitizedPrefix = sanitize(namespacePrefix).toLowerCase(Locale.ROOT);
        try {
            KeyStore keyStore = loadStore();
            java.util.ArrayList<String> refs = new java.util.ArrayList<>();
            var aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                String canonicalAlias = alias.toLowerCase(Locale.ROOT);
                if (canonicalAlias.startsWith(sanitizedPrefix)) {
                    refs.add(REF_PREFIX + canonicalAlias);
                }
            }
            refs.sort(String::compareTo);
            return List.copyOf(refs);
        } catch (Exception ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_LIST_FAILED", "枚举本地 KeyStore 引用失败");
        }
    }

    /**
     * 读取 secretRef 指向的明文密钥。
     *
     * @param secretRef `save` 返回的引用
     * @return 找到时返回明文；引用不是 KeyStore 引用或条目不存在时返回空
     */
    @Override
    public synchronized Optional<String> load(String secretRef) {
        Optional<char[]> chars = loadChars(secretRef);
        if (chars.isEmpty()) return Optional.empty();
        char[] value = chars.get();
        try {
            return Optional.of(new String(value));
        } finally {
            java.util.Arrays.fill(value, '\0');
        }
    }

    @Override
    public synchronized Optional<char[]> loadChars(String secretRef) {
        String alias = aliasFrom(secretRef);
        if (alias == null) {
            return Optional.empty();
        }
        try {
            KeyStore keyStore = loadStore();
            if (!keyStore.containsAlias(alias)) {
                return Optional.empty();
            }
            KeyStore.Entry entry = withPasswordProtection(protection -> keyStore.getEntry(alias, protection));
            if (!(entry instanceof KeyStore.SecretKeyEntry secretEntry)) {
                return Optional.empty();
            }
            SecretKey secretKey = secretEntry.getSecretKey();
            byte[] encoded = secretKey.getEncoded();
            try {
                return Optional.of(decodeUtf8(encoded));
            } finally {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
        } catch (Exception ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_READ_FAILED", "读取本地 KeyStore 密钥失败");
        }
    }

    /**
     * 删除 secretRef 指向的密钥。
     *
     * @param secretRef `save` 返回的引用
     */
    @Override
    public synchronized void delete(String secretRef) {
        String alias = aliasFrom(secretRef);
        if (alias == null) {
            return;
        }
        try {
            KeyStore keyStore = loadStore();
            if (!keyStore.containsAlias(alias)) {
                return;
            }
            keyStore.deleteEntry(alias);
            store(keyStore);
        } catch (Exception ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_DELETE_FAILED", "删除本地 KeyStore 密钥失败");
        }
    }

    private void writeSecret(String alias, char[] secretChars) {
        try {
            KeyStore keyStore = loadStore();
            if (keyStore.containsAlias(alias)) {
                throw new SecretStoreException(
                        "SECRET_STORE_REFERENCE_EXISTS", "SecretStore 引用已存在");
            }
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(secretChars));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            try {
                SecretKeySpec secretKey = new SecretKeySpec(bytes, SECRET_ALGORITHM);
                withPasswordProtection(protection -> {
                    keyStore.setEntry(alias, new KeyStore.SecretKeyEntry(secretKey), protection);
                    return null;
                });
                store(keyStore);
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
                java.util.Arrays.fill(encoded.array(), (byte) 0);
            }
        } catch (SecretStoreException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new SecretStoreException(
                    "SECRET_STORE_WRITE_FAILED", "写入本地 KeyStore 密钥失败");
        }
    }

    private KeyStore loadStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
        if (Files.notExists(storePath)) {
            keyStore.load(null, password);
            return keyStore;
        }
        try (InputStream input = Files.newInputStream(storePath)) {
            keyStore.load(input, password);
            return keyStore;
        }
    }

    private void store(KeyStore keyStore) throws IOException, KeyStoreException, java.security.NoSuchAlgorithmException,
            java.security.cert.CertificateException {
        Path targetPath = storePath.toAbsolutePath().normalize();
        Path parent = targetPath.getParent();
        if (parent == null) {
            throw new IOException("KeyStore 目标文件缺少父目录: " + targetPath);
        }
        Files.createDirectories(parent);
        String fileName = targetPath.getFileName() == null
                ? "babiq-secrets"
                : targetPath.getFileName().toString();
        String temporaryPrefix = fileName.length() >= 3 ? fileName : (fileName + "___").substring(0, 3);
        Path temporaryPath = Files.createTempFile(parent, temporaryPrefix + "-", ".tmp");
        boolean committed = false;
        try {
            try (OutputStream output = Files.newOutputStream(temporaryPath)) {
                keyStore.store(output, password);
            }
            try {
                storeFileCommitter.commit(temporaryPath, targetPath);
            } catch (IOException commitFailure) {
                throw commitFailure;
            }
            committed = true;
        } finally {
            try {
                Files.deleteIfExists(temporaryPath);
            } catch (IOException cleanupFailure) {
                if (!committed) {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static char[] decodeUtf8(byte[] encoded) throws CharacterCodingException {
        CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded));
        try {
            char[] value = new char[chars.remaining()];
            chars.get(value);
            return value;
        } finally {
            if (chars.hasArray()) java.util.Arrays.fill(chars.array(), '\0');
        }
    }

    private <T> T withPasswordProtection(PasswordProtectionOperation<T> operation) throws Exception {
        KeyStore.PasswordProtection protection = passwordProtectionFactory.create(password);
        if (protection == null) {
            throw new IllegalStateException("PasswordProtectionFactory 未返回保护对象");
        }
        Throwable primaryFailure = null;
        try {
            return operation.apply(protection);
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                protection.destroy();
            } catch (Exception | Error cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static String sanitize(String namespace) {
        String rawNamespace = namespace == null || namespace.isBlank() ? "default" : namespace;
        return rawNamespace.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String aliasFrom(String secretRef) {
        if (secretRef == null || !secretRef.startsWith(REF_PREFIX)) {
            return null;
        }
        String alias = secretRef.substring(REF_PREFIX.length());
        return alias.isBlank() ? null : alias;
    }
}
