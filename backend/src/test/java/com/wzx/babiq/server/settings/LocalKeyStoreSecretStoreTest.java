package com.wzx.babiq.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地 KeyStore SecretStore 测试。
 *
 * <p>P2-3 要求 Provider 表只能保存 secretRef，因此这里先用真实临时文件验证：
 * 明文 API Key 能写入 KeyStore、能跨实例读取、删除后不能再读到。</p>
 */
class LocalKeyStoreSecretStoreTest {

    /** 每个测试独立目录，避免不同测试之间共享本地密钥文件。 */
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("KeyStore SecretStore 能保存、重启后读取并删除密钥")
    void keystore_secret_store_should_persist_load_and_delete_secret() {
        Path storePath = tempDir.resolve("babiq-secrets.jceks");
        char[] password = "test-store-password".toCharArray();

        LocalKeyStoreSecretStore firstStore = new LocalKeyStoreSecretStore(storePath, password);
        String secretRef = firstStore.save("provider.deepseek", "sk-local-secret");

        assertThat(secretRef).startsWith("keystore://");
        assertThat(firstStore.load(secretRef)).contains("sk-local-secret");

        LocalKeyStoreSecretStore reopenedStore = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopenedStore.load(secretRef)).contains("sk-local-secret");

        reopenedStore.delete(secretRef);
        assertThat(reopenedStore.load(secretRef)).isEmpty();
    }

    @Test
    @DisplayName("SecretStore 读不到密钥时给出明确异常")
    void keystore_secret_store_should_reject_unknown_secret_ref_when_required() {
        LocalKeyStoreSecretStore secretStore = new LocalKeyStoreSecretStore(
                tempDir.resolve("missing-secret.jceks"),
                "test-store-password".toCharArray());

        assertThat(secretStore.load("keystore://missing")).isEmpty();
        assertThatThrownBy(() -> secretStore.require("keystore://missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密钥不存在");
    }
}
