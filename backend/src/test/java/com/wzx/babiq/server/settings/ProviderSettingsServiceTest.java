package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.persistence.service.ProviderPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider 设置服务测试。
 *
 * <p>服务层是 P2-3 Provider 编辑的核心边界：桌面端传入明文 API Key 后，
 * 这里必须立刻交给 SecretStore，只把 secretRef 写入 SQLite。</p>
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class ProviderSettingsServiceTest {

    /** 用于证明异常、对象字符串和日志都不会回显的假密钥标记。 */
    private static final String SENSITIVE_MARKER = "sk-fake-sensitive-marker";

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
    private ModelProviderRegistry providerRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private SecretStore secretStore;
    @MockitoBean
    private ChatClientFactory chatClientFactory;
    @MockitoBean
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
                "claude-oauth-created",
                "Claude OAuth",
                "ANTHROPIC",
                "oauth_cli",
                "",
                "claude-sonnet-4-6",
                null,
                0,
                true);

        ProviderSettingsService.ProviderView view = providerSettingsService.create(draft);

        ProviderConfigRecord saved = providerPersistenceService.findProvider("claude-oauth-created").orElseThrow();
        assertThat(view.authMode()).isEqualTo("oauth_cli");
        assertThat(view.hasApiKey()).isFalse();
        assertThat(saved.authMode()).isEqualTo("oauth_cli");
        assertThat(saved.secretRef()).isNull();
        assertThat(saved.baseUrl()).isEmpty();
    }

    @Test
    @DisplayName("重复创建 Provider 时不得覆盖已有配置和旧密钥")
    void duplicate_create_must_not_overwrite_existing_provider(CapturedOutput output) {
        String providerId = "duplicate-create-provider";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-first-provider-secret", "gpt-4o-mini"));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        String activeBefore = providerRegistry.active().id();
        clearInvocations(secretStore, chatClientFactory);

        ProviderSettingsService.ProviderDraft duplicate = apiKeyDraft(
                providerId, SENSITIVE_MARKER, "should-not-replace-model");
        Throwable failure = catchThrowable(() -> providerSettingsService.create(duplicate));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
        assertThat(failure.toString().contains(SENSITIVE_MARKER)).isFalse();
        ProviderConfigRecord after = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(after).isEqualTo(before);
        assertThat(secretStore.load(after.secretRef())).contains("sk-first-provider-secret");
        assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        verify(secretStore, never()).save(eq("provider." + providerId), eq(SENSITIVE_MARKER));
        verify(chatClientFactory, never()).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
    }

    @Test
    @DisplayName("从 OAuth 或无密钥模式切换到 API Key 时必须提交新密钥")
    void switching_from_oauth_to_api_key_requires_new_key() {
        String providerId = "oauth-to-api-key-provider";
        providerSettingsService.create(oauthDraft(providerId));

        assertThat(catchThrowable(() -> providerSettingsService.update(
                apiKeyDraft(providerId, "", "claude-sonnet-4-6"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apiKey");

        ProviderConfigRecord saved = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(saved.authMode()).isEqualTo("oauth_cli");
        assertThat(saved.secretRef()).isNull();
    }

    @Test
    @DisplayName("更新已有 API Key Provider 时空白密钥沿用旧引用且不创建或删除 alias")
    void updating_api_key_provider_with_blank_key_reuses_existing_secret_without_alias_changes() {
        String providerId = "blank-key-reuses-existing-secret";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-existing-secret", "gpt-4o-mini"));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        clearInvocations(secretStore, chatClientFactory);

        ProviderSettingsService.ProviderView view = providerSettingsService.update(
                apiKeyDraft(providerId, "   ", "gpt-4.1-mini"));

        ProviderConfigRecord after = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(view.hasApiKey()).isTrue();
        assertThat(after.secretRef()).isEqualTo(before.secretRef());
        assertThat(after.model()).isEqualTo("gpt-4.1-mini");
        assertThat(secretStore.load(after.secretRef())).contains("sk-existing-secret");
        assertThat(providerRegistry.get(providerId).apiKey()).isEqualTo("sk-existing-secret");
        verify(secretStore, never()).save(anyString(), anyString());
        verify(secretStore, never()).delete(anyString());
        verify(chatClientFactory).invalidate(providerId);
    }

    @Test
    @DisplayName("更新未知 Provider 时在任何密钥或运行时副作用前拒绝")
    void updating_unknown_provider_rejects_without_creating_record_or_runtime_side_effects() {
        String providerId = "unknown-provider-update";
        List<String> providerIdsBefore = providerIds();
        String activeBefore = providerRegistry.active().id();
        clearInvocations(secretStore, chatClientFactory);

        Throwable failure = catchThrowable(() -> providerSettingsService.update(
                apiKeyDraft(providerId, "sk-must-not-be-stored", "gpt-4o-mini")));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        assertThat(providerPersistenceService.findProvider(providerId)).isEmpty();
        assertThat(providerIds()).containsExactlyElementsOf(providerIdsBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        verify(secretStore, never()).save(anyString(), anyString());
        verify(secretStore, never()).delete(anyString());
        verify(chatClientFactory, never()).invalidate(providerId);
    }

    @Test
    @DisplayName("数据库 insert 失败时删除新密钥且不修改运行时 Provider")
    void failed_database_insert_deletes_new_secret_alias_and_keeps_runtime_unchanged(CapturedOutput output) {
        String providerId = "failed-provider-insert";
        AtomicReference<String> stagedSecretRef = captureSavedSecret(
                "provider." + providerId, SENSITIVE_MARKER);
        List<String> providerIdsBefore = providerIds();
        String activeBefore = providerRegistry.active().id();
        installFailureTrigger("fail_provider_insert", "INSERT", providerId);
        try {
            Throwable failure = catchThrowable(() -> providerSettingsService.create(
                    apiKeyDraft(providerId, SENSITIVE_MARKER, "gpt-4o-mini")));

            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(failure.toString().contains(SENSITIVE_MARKER)).isFalse();
            assertThat(stagedSecretRef.get()).isNotBlank();
            assertThat(secretStore.load(stagedSecretRef.get()).isPresent()).isFalse();
            assertThat(providerPersistenceService.findProvider(providerId)).isEmpty();
            assertThat(providerIds()).containsExactlyElementsOf(providerIdsBefore);
            assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
            verify(chatClientFactory, never()).invalidate(providerId);
            assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
        } finally {
            dropTrigger("fail_provider_insert");
        }
    }

    @Test
    @DisplayName("数据库 update 失败时删除新密钥且保留已提交配置")
    void failed_database_update_deletes_new_secret_alias_and_keeps_committed_runtime(CapturedOutput output) {
        String providerId = "failed-provider-update";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-before-update", "gpt-4o-mini"));
        ProviderConfigRecord persistedBefore = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        String activeBefore = providerRegistry.active().id();
        clearInvocations(secretStore, chatClientFactory);
        AtomicReference<String> stagedSecretRef = captureSavedSecret(
                "provider." + providerId, SENSITIVE_MARKER);
        installFailureTrigger("fail_provider_update", "UPDATE", providerId);
        try {
            Throwable failure = catchThrowable(() -> providerSettingsService.update(
                    apiKeyDraft(providerId, SENSITIVE_MARKER, "should-not-commit-model")));

            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(failure.toString().contains(SENSITIVE_MARKER)).isFalse();
            assertThat(stagedSecretRef.get()).isNotBlank();
            assertThat(secretStore.load(stagedSecretRef.get()).isPresent()).isFalse();
            assertThat(providerPersistenceService.findProvider(providerId)).contains(persistedBefore);
            assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
            assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
            verify(chatClientFactory, never()).invalidate(providerId);
            assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
        } finally {
            dropTrigger("fail_provider_update");
        }
    }

    @Test
    @DisplayName("成功轮换 API Key 后先提交新引用再删除旧密钥")
    void successful_key_rotation_activates_new_ref_and_deletes_old_alias_after_commit(CapturedOutput output) {
        String providerId = "successful-key-rotation";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-before-rotation", "gpt-4o-mini"));
        String oldSecretRef = providerPersistenceService.findProvider(providerId).orElseThrow().secretRef();
        clearInvocations(secretStore, chatClientFactory);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ProviderConfigRecord committed = providerPersistenceService.findProvider(providerId).orElseThrow();
            assertThat(committed.secretRef()).isNotEqualTo(oldSecretRef);
            return invocation.callRealMethod();
        }).when(secretStore).delete(oldSecretRef);

        ProviderSettingsService.ProviderView view = providerSettingsService.update(
                apiKeyDraft(providerId, SENSITIVE_MARKER, "gpt-4.1-mini"));

        ProviderConfigRecord saved = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(view.hasApiKey()).isTrue();
        assertThat(saved.secretRef()).isNotEqualTo(oldSecretRef);
        assertThat(secretStore.load(oldSecretRef)).isEmpty();
        assertThat(secretStore.load(saved.secretRef()).orElseThrow().equals(SENSITIVE_MARKER)).isTrue();
        assertThat(providerRegistry.get(providerId).apiKey().equals(SENSITIVE_MARKER)).isTrue();
        verify(secretStore).delete(oldSecretRef);
        verify(chatClientFactory).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
    }

    @Test
    @DisplayName("切换到 OAuth 后提交空 secretRef 并删除旧 API Key")
    void switching_to_oauth_clears_secret_ref_and_deletes_old_alias_after_commit() {
        String providerId = "switch-to-oauth-provider";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-before-oauth", "gpt-4o-mini"));
        String oldSecretRef = providerPersistenceService.findProvider(providerId).orElseThrow().secretRef();
        clearInvocations(secretStore, chatClientFactory);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(providerPersistenceService.findProvider(providerId).orElseThrow().secretRef()).isNull();
            return invocation.callRealMethod();
        }).when(secretStore).delete(oldSecretRef);

        ProviderSettingsService.ProviderView view = providerSettingsService.update(oauthDraft(providerId));

        ProviderConfigRecord saved = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(view.authMode()).isEqualTo("oauth_cli");
        assertThat(view.hasApiKey()).isFalse();
        assertThat(saved.secretRef()).isNull();
        assertThat(secretStore.load(oldSecretRef)).isEmpty();
        assertThat(providerRegistry.get(providerId).apiKey()).isNull();
        verify(secretStore).delete(oldSecretRef);
        verify(chatClientFactory).invalidate(providerId);
    }

    @Test
    @DisplayName("Provider 草稿、异常和日志不得回显 API Key")
    void provider_draft_exception_and_logs_must_not_expose_api_key(CapturedOutput output) {
        ProviderSettingsService.ProviderDraft draft = new ProviderSettingsService.ProviderDraft(
                "safe-string-provider",
                "安全字符串 Provider",
                "NOT_A_PROVIDER_TYPE",
                "api_key",
                "https://relay.example.com/v1",
                "gpt-4o-mini",
                SENSITIVE_MARKER,
                64000,
                true);

        Throwable failure = catchThrowable(() -> providerSettingsService.create(draft));

        assertThat(draft.toString().contains(SENSITIVE_MARKER)).isFalse();
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure.toString().contains(SENSITIVE_MARKER)).isFalse();
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
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

    /**
     * 构造 API Key Provider 草稿；测试只改变模型和密钥，其他字段保持稳定。
     */
    private static ProviderSettingsService.ProviderDraft apiKeyDraft(String providerId, String apiKey, String model) {
        return new ProviderSettingsService.ProviderDraft(
                providerId,
                providerId,
                "OPENAI_COMPATIBLE",
                "api_key",
                "https://relay.example.com/v1",
                model,
                apiKey,
                64000,
                true);
    }

    /**
     * 构造 Anthropic OAuth CLI 草稿；该模式不保存 API Key。
     */
    private static ProviderSettingsService.ProviderDraft oauthDraft(String providerId) {
        return new ProviderSettingsService.ProviderDraft(
                providerId,
                providerId,
                "ANTHROPIC",
                "oauth_cli",
                "",
                "claude-sonnet-4-6",
                null,
                0,
                true);
    }

    /**
     * 监听真实 SecretStore.save 的返回引用，供失败路径验证补偿删除。
     */
    private AtomicReference<String> captureSavedSecret(String namespace, String secret) {
        AtomicReference<String> savedRef = new AtomicReference<>();
        doAnswer(invocation -> {
            String secretRef = (String) invocation.callRealMethod();
            savedRef.set(secretRef);
            return secretRef;
        }).when(secretStore).save(eq(namespace), eq(secret));
        return savedRef;
    }

    /**
     * 安装只针对单个 Provider 的 SQLite 失败 trigger，模拟真实 insert/update 提交失败。
     */
    private void installFailureTrigger(String triggerName, String operation, String providerId) {
        jdbcTemplate.execute(("""
                CREATE TRIGGER %s BEFORE %s ON bq_provider_configs
                WHEN NEW.provider_id = '%s'
                BEGIN
                    SELECT RAISE(FAIL, 'db-failed');
                END
                """).formatted(triggerName, operation, providerId));
    }

    /** 删除测试注入的 SQLite trigger，避免影响同一 Spring 上下文中的后续用例。 */
    private void dropTrigger(String triggerName) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName);
    }

    /** 返回当前运行时 Provider ID 顺序快照。 */
    private List<String> providerIds() {
        return providerRegistry.list().stream().map(ModelProviderConfig::id).toList();
    }
}
