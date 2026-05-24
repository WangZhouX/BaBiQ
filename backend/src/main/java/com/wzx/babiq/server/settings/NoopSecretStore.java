package com.wzx.babiq.server.settings;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期内存版 SecretStore。
 *
 * <p>Noop 实现只服务 P2-1 和本地测试：它把密钥存在内存 Map 中，应用重启后会丢失。即便如此，
 * 外部仍只能拿到 `noop://...` 引用，用来验证 Provider 表不会误写明文 API Key。</p>
 */
@Component
public class NoopSecretStore implements SecretStore {

    /** secretRef -> 明文密钥；仅开发期使用，生产级持久化实现留到 P2-3。 */
    private final Map<String, String> secrets = new ConcurrentHashMap<>();

    @Override
    public String save(String namespace, String secretPlainText) {
        String sanitizedNamespace = namespace == null || namespace.isBlank() ? "default" : namespace;
        String secretRef = "noop://" + sanitizedNamespace + "/" + UUID.randomUUID();
        secrets.put(secretRef, secretPlainText);
        return secretRef;
    }

    @Override
    public Optional<String> load(String secretRef) {
        return Optional.ofNullable(secrets.get(secretRef));
    }

    @Override
    public void delete(String secretRef) {
        secrets.remove(secretRef);
    }
}
