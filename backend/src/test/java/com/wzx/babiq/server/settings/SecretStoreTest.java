package com.wzx.babiq.server.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecretStore 安全边界测试。
 *
 * <p>P2-1 只定义密钥存储抽象和开发期 Noop 实现，真实 KeyStore 留到 P2-3。
 * 但即使是 Noop，也必须用 secretRef 作为外部引用，避免 Provider 表写入明文 key。</p>
 */
class SecretStoreTest {

    @Test
    @DisplayName("保存密钥后返回 secretRef，并能通过 secretRef 读取")
    void save_should_return_secret_ref_and_load_by_reference() {
        SecretStore secretStore = new NoopSecretStore();

        String secretRef = secretStore.save("provider.deepseek", "sk-test");

        assertThat(secretRef).startsWith("noop://provider.deepseek/");
        assertThat(secretStore.load(secretRef)).contains("sk-test");
    }

    @Test
    @DisplayName("删除 secretRef 后不能再读取明文")
    void delete_should_remove_secret() {
        SecretStore secretStore = new NoopSecretStore();
        String secretRef = secretStore.save("provider.deepseek", "sk-test");

        secretStore.delete(secretRef);

        assertThat(secretStore.load(secretRef)).isEmpty();
    }
}
