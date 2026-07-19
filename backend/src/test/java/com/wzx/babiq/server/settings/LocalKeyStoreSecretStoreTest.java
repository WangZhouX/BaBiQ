package com.wzx.babiq.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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

    @Test
    @DisplayName("保存新密钥的文件提交失败时原 KeyStore 文件和旧 entry 保持不变")
    void failed_save_keeps_original_keystore_file_and_existing_entries() throws Exception {
        Path storePath = tempDir.resolve("save-failure.jceks");
        char[] password = "test-store-password".toCharArray();
        LocalKeyStoreSecretStore stableStore = new LocalKeyStoreSecretStore(storePath, password);
        String existingRef = stableStore.save("provider.existing", "sk-existing");
        byte[] originalFile = Files.readAllBytes(storePath);
        LocalKeyStoreSecretStore failingStore = new LocalKeyStoreSecretStore(
                storePath, password, controlledCommitFailure());

        assertThatThrownBy(() -> failingStore.save("provider.new", "sk-new"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入本地 KeyStore 密钥失败");

        assertThat(Files.readAllBytes(storePath)).containsExactly(originalFile);
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopened.load(existingRef)).contains("sk-existing");
        assertNoTemporaryStoreFiles();
    }

    @Test
    @DisplayName("删除密钥的文件提交失败时原 KeyStore 文件和原 entry 保持不变")
    void failed_delete_keeps_original_keystore_file_and_entry() throws Exception {
        Path storePath = tempDir.resolve("delete-failure.jceks");
        char[] password = "test-store-password".toCharArray();
        LocalKeyStoreSecretStore stableStore = new LocalKeyStoreSecretStore(storePath, password);
        String existingRef = stableStore.save("provider.existing", "sk-existing");
        byte[] originalFile = Files.readAllBytes(storePath);
        LocalKeyStoreSecretStore failingStore = new LocalKeyStoreSecretStore(
                storePath, password, controlledCommitFailure());

        assertThatThrownBy(() -> failingStore.delete(existingRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("删除本地 KeyStore 密钥失败");

        assertThat(Files.readAllBytes(storePath)).containsExactly(originalFile);
        LocalKeyStoreSecretStore reopened = new LocalKeyStoreSecretStore(storePath, password);
        assertThat(reopened.load(existingRef)).contains("sk-existing");
        assertNoTemporaryStoreFiles();
    }

    /** 构造发生在目标文件替换前的可控提交失败。 */
    private static LocalKeyStoreSecretStore.StoreFileCommitter controlledCommitFailure() {
        return (temporaryPath, targetPath) -> {
            throw new IOException("controlled-store-commit-failure");
        };
    }

    /** 原子提交失败后不得遗留本地临时 KeyStore 文件。 */
    private void assertNoTemporaryStoreFiles() throws IOException {
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }
}
