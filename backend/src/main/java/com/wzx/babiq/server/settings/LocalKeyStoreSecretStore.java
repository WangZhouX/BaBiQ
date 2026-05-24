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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
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

    /** KeyStore 文件路径，默认位于用户目录下的 `.babiq`。 */
    private final Path storePath;
    /** KeyStore 文件和条目共用的本地保护口令。 */
    private final char[] password;

    /**
     * Spring 生产构造器。
     *
     * @param storePath 配置项 `babiq.secrets.keystore-path`
     * @param password 配置项 `babiq.secrets.keystore-password`
     */
    @Autowired
    public LocalKeyStoreSecretStore(
            @Value("${babiq.secrets.keystore-path:${user.home}/.babiq/babiq-secrets.jceks}") String storePath,
            @Value("${babiq.secrets.keystore-password:babiq-local-secret-store}") String password) {
        this(Path.of(storePath), password.toCharArray());
    }

    /**
     * 测试和手动构造器。
     *
     * @param storePath KeyStore 文件路径
     * @param password 本地保护口令
     */
    public LocalKeyStoreSecretStore(Path storePath, char[] password) {
        this.storePath = storePath;
        this.password = password.clone();
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
        String alias = sanitize(namespace) + "-" + UUID.randomUUID().toString().replace("-", "");
        writeSecret(alias, secretPlainText);
        return REF_PREFIX + alias;
    }

    /**
     * 读取 secretRef 指向的明文密钥。
     *
     * @param secretRef `save` 返回的引用
     * @return 找到时返回明文；引用不是 KeyStore 引用或条目不存在时返回空
     */
    @Override
    public synchronized Optional<String> load(String secretRef) {
        String alias = aliasFrom(secretRef);
        if (alias == null) {
            return Optional.empty();
        }
        try {
            KeyStore keyStore = loadStore();
            if (!keyStore.containsAlias(alias)) {
                return Optional.empty();
            }
            KeyStore.Entry entry = keyStore.getEntry(alias, protection());
            if (!(entry instanceof KeyStore.SecretKeyEntry secretEntry)) {
                return Optional.empty();
            }
            SecretKey secretKey = secretEntry.getSecretKey();
            return Optional.of(new String(secretKey.getEncoded(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("读取本地 KeyStore 密钥失败: " + alias, exception);
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
        } catch (Exception exception) {
            throw new IllegalStateException("删除本地 KeyStore 密钥失败: " + alias, exception);
        }
    }

    private void writeSecret(String alias, String secretPlainText) {
        try {
            KeyStore keyStore = loadStore();
            SecretKeySpec secretKey = new SecretKeySpec(secretPlainText.getBytes(StandardCharsets.UTF_8), SECRET_ALGORITHM);
            keyStore.setEntry(alias, new KeyStore.SecretKeyEntry(secretKey), protection());
            store(keyStore);
        } catch (Exception exception) {
            throw new IllegalStateException("写入本地 KeyStore 密钥失败: " + alias, exception);
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
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream output = Files.newOutputStream(storePath)) {
            keyStore.store(output, password);
        }
    }

    private KeyStore.PasswordProtection protection() {
        return new KeyStore.PasswordProtection(password);
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
