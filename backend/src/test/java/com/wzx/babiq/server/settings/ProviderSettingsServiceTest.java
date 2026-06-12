package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.persistence.service.ProviderPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider 设置服务测试。
 *
 * <p>服务层是 P2-3 Provider 编辑的核心边界：桌面端传入明文 API Key 后，
 * 这里必须立刻交给 SecretStore，只把 secretRef 写入 SQLite。</p>
 */
@SpringBootTest
class ProviderSettingsServiceTest {

    /** 每次测试使用独立 SQLite 文件，避免真实用户配置影响断言。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "provider-settings-" + UUID.randomUUID() + ".db").toAbsolutePath();

    /** 每次测试使用独立 KeyStore 文件，避免真实密钥文件参与测试。 */
    private static final Path TEST_KEYSTORE = Path.of("target", "test-db",
            "provider-settings-" + UUID.randomUUID() + ".jceks").toAbsolutePath();

    @DynamicPropertySource
    static void settingsProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
        registry.add("babiq.secrets.keystore-path", () -> TEST_KEYSTORE.toString());
        registry.add("babiq.secrets.keystore-password", () -> "test-store-password");
    }

    @Autowired
    private ProviderSettingsService providerSettingsService;
    @Autowired
    private ProviderPersistenceService providerPersistenceService;
    @Autowired
    private SecretStore secretStore;
    @MockBean
    private AnthropicOAuthCredentialSource anthropicOAuthCredentialSource;

    @Test
    @DisplayName("创建 Provider 时明文 API Key 进入 SecretStore，数据库只保存 secretRef")
    void create_provider_should_store_api_key_in_secret_store_only() {
        ProviderSettingsService.ProviderDraft draft = new ProviderSettingsService.ProviderDraft(
                "custom-openai",
                "自定义 OpenAI",
                "OPENAI_COMPATIBLE",
                "api_key",
                "https://relay.example.com/v1",
                "deepseek-chat",
                "sk-test-provider-secret",
                128000,
                true);

        ProviderSettingsService.ProviderView view = providerSettingsService.create(draft);

        ProviderConfigRecord saved = providerPersistenceService.findProvider("custom-openai").orElseThrow();
        assertThat(view.id()).isEqualTo("custom-openai");
        assertThat(view.hasApiKey()).isTrue();
        assertThat(view.apiKey()).isNull();
        assertThat(saved.secretRef()).startsWith("keystore://");
        assertThat(saved.secretRef()).doesNotContain("sk-test-provider-secret");
        assertThat(saved.toString()).doesNotContain("sk-test-provider-secret");
        assertThat(secretStore.load(saved.secretRef())).contains("sk-test-provider-secret");
    }

    @Test
    @DisplayName("创建 Anthropic OAuth CLI Provider 时不要求也不保存 API Key")
    void create_anthropic_oauth_cli_provider_should_not_require_or_store_api_key() {
        ProviderSettingsService.ProviderDraft draft = new ProviderSettingsService.ProviderDraft(
                "claude-oauth",
                "Claude OAuth",
                "ANTHROPIC",
                "oauth_cli",
                "",
                "claude-sonnet-4-6",
                null,
                0,
                true);

        ProviderSettingsService.ProviderView view = providerSettingsService.create(draft);

        ProviderConfigRecord saved = providerPersistenceService.findProvider("claude-oauth").orElseThrow();
        assertThat(view.authMode()).isEqualTo("oauth_cli");
        assertThat(view.hasApiKey()).isFalse();
        assertThat(saved.authMode()).isEqualTo("oauth_cli");
        assertThat(saved.secretRef()).isNull();
        assertThat(saved.baseUrl()).isEmpty();
    }

    @Test
    @DisplayName("测试 Anthropic OAuth Provider 时必须检查 CLI 登录状态")
    void test_connection_should_check_anthropic_oauth_cli_login_state() {
        providerSettingsService.create(new ProviderSettingsService.ProviderDraft(
                "claude-oauth-test",
                "Claude OAuth Test",
                "ANTHROPIC",
                "oauth_cli",
                "",
                "claude-sonnet-4-6",
                null,
                0,
                true));
        when(anthropicOAuthCredentialSource.accessToken())
                .thenThrow(new IllegalStateException("请先运行 ant auth login；如命中 Apache Ant 请配置 cli-path"));

        ProviderSettingsService.ProviderTestResult result = providerSettingsService.testConnection("claude-oauth-test");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("ant auth login").contains("cli-path");
        verify(anthropicOAuthCredentialSource).accessToken();
    }

    @Test
    @DisplayName("删除 Provider 后默认列表不再返回，但历史 turn 不会被物理删除")
    void delete_provider_should_hide_it_from_enabled_provider_list() {
        providerSettingsService.create(new ProviderSettingsService.ProviderDraft(
                "delete-me",
                "待删除",
                "OPENAI_COMPATIBLE",
                "api_key",
                "https://relay.example.com/v1",
                "gpt-4o-mini",
                "sk-delete-me",
                64000,
                true));

        providerSettingsService.delete("delete-me");

        assertThat(providerSettingsService.listEnabled())
                .extracting(ProviderSettingsService.ProviderView::id)
                .doesNotContain("delete-me");
        assertThat(providerPersistenceService.findProvider("delete-me"))
                .get()
                .extracting(ProviderConfigRecord::enabled)
                .isEqualTo(false);
    }
}
