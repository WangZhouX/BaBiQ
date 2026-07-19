package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.conversation.repository.AppSettingRecord;
import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.model.provider.ProviderFactory;
import com.wzx.babiq.server.persistence.service.AppSettingPersistenceService;
import com.wzx.babiq.server.persistence.service.ProviderPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.ai.chat.model.ChatModel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private AppSettingPersistenceService appSettingPersistenceService;
    @Autowired
    private AppSettingsService appSettingsService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private BaBiQProperties baBiQProperties;
    @MockitoSpyBean
    private ModelProviderRegistry providerRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private SecretStore secretStore;
    @MockitoBean
    private ChatClientFactory chatClientFactory;
    @MockitoBean
    private AnthropicOAuthCredentialSource anthropicOAuthCredentialSource;

    /**
     * 每个用例清理动态 Provider、密钥和 active 设置，避免静态 SQLite 累积掩盖最后 Provider 语义。
     */
    @AfterEach
    void cleanupDynamicProviderState() {
        org.mockito.Mockito.reset(secretStore, providerRegistry, chatClientFactory, anthropicOAuthCredentialSource);
        List<String> baselineProviderIds = baBiQProperties.providers().stream()
                .map(ModelProviderConfig::id)
                .toList();
        for (ProviderConfigRecord record : providerPersistenceService.listProviders(false)) {
            if (baselineProviderIds.contains(record.providerId())) {
                continue;
            }
            if (record.secretRef() != null && !record.secretRef().isBlank()) {
                secretStore.delete(record.secretRef());
            }
            providerPersistenceService.hardDeleteProviderForCompensation(record.providerId());
        }
        for (ModelProviderConfig config : providerRegistry.list()) {
            if (!baselineProviderIds.contains(config.id())) {
                providerRegistry.disable(config.id());
            }
        }
        baBiQProperties.providers().forEach(providerRegistry::registerOrUpdate);
        providerRegistry.setActive(baBiQProperties.activeProvider());
        jdbcTemplate.update("DELETE FROM bq_app_settings");
    }

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
    @DisplayName("更新不得改变 enabled 状态或禁用当前 Provider")
    void update_rejects_enabled_state_change_without_touching_database_runtime_or_active() {
        String providerId = "update-enabled-state-rejected";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-enabled-before", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        AppSettingRecord activeBefore = appSettingPersistenceService.findByKey(
                AppSettingsService.KEY_ACTIVE_PROVIDER).orElseThrow();
        ProviderSettingsService.ProviderDraft disabledDraft = new ProviderSettingsService.ProviderDraft(
                providerId, providerId, "OPENAI_COMPATIBLE", "api_key",
                "https://relay.example.com/v1", "gpt-4o-mini", "", 64000, false);

        Throwable failure = catchThrowable(() -> providerSettingsService.update(disabledDraft));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(before);
        assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(providerId);
        assertThat(appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER))
                .contains(activeBefore);
    }

    @Test
    @DisplayName("已禁用 Provider 不得通过 update 复活或继续编辑")
    void update_rejects_disabled_provider_without_staging_new_secret_or_runtime() {
        String providerId = "disabled-provider-update-rejected";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-disabled-before", "gpt-4o-mini"));
        providerSettingsService.delete(providerId);
        ProviderConfigRecord disabledBefore = providerPersistenceService.findProvider(providerId).orElseThrow();
        clearInvocations(secretStore, chatClientFactory);

        Throwable failure = catchThrowable(() -> providerSettingsService.update(
                apiKeyDraft(providerId, SENSITIVE_MARKER, "must-not-revive")));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已禁用");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(disabledBefore);
        assertThatThrownBy(() -> providerRegistry.get(providerId))
                .isInstanceOf(IllegalArgumentException.class);
        verify(secretStore, never()).save(eq("provider." + providerId), eq(SENSITIVE_MARKER));
        verify(chatClientFactory, never()).invalidate(providerId);
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
    @DisplayName("创建 Provider 时新密钥解析失败必须发生在 SQLite 写入前并补偿 alias")
    void create_runtime_secret_resolution_failure_happens_before_database_write_and_compensates_alias(
            CapturedOutput output) {
        String providerId = "create-runtime-resolution-failure";
        AtomicReference<String> stagedSecretRef = captureSavedSecret(
                "provider." + providerId, "sk-create-runtime-failure");
        List<String> providerIdsBefore = providerIds();
        String activeBefore = providerRegistry.active().id();
        doAnswer(invocation -> {
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(secretStore).require(anyString());

        Throwable failure = catchThrowable(() -> providerSettingsService.create(
                apiKeyDraft(providerId, "sk-create-runtime-failure", "gpt-4o-mini")));

        assertSafeFailure(failure, "运行时配置");
        assertThat(stagedSecretRef.get()).isNotBlank();
        assertThat(secretStore.load(stagedSecretRef.get()).isPresent()).isFalse();
        assertThat(providerPersistenceService.findProvider(providerId)).isEmpty();
        assertThat(providerIds()).containsExactlyElementsOf(providerIdsBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        verify(secretStore).delete(stagedSecretRef.get());
        verify(chatClientFactory, never()).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
    }

    @Test
    @DisplayName("沿用旧密钥的运行时解析失败必须发生在 SQLite 更新前")
    void reused_secret_resolution_failure_happens_before_database_update(CapturedOutput output) {
        String providerId = "reuse-secret-runtime-resolution-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-existing-runtime", "gpt-4o-mini"));
        ProviderConfigRecord persistedBefore = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        String activeBefore = providerRegistry.active().id();
        clearInvocations(secretStore, chatClientFactory);
        doAnswer(invocation -> {
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(secretStore).require(persistedBefore.secretRef());

        Throwable failure = catchThrowable(() -> providerSettingsService.update(
                apiKeyDraft(providerId, "   ", "should-not-commit-model")));

        assertSafeFailure(failure, "运行时配置");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(persistedBefore);
        assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        assertThat(secretStore.load(persistedBefore.secretRef())).contains("sk-existing-runtime");
        verify(secretStore, never()).save(anyString(), anyString());
        verify(secretStore, never()).delete(anyString());
        verify(chatClientFactory, never()).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
    }

    @Test
    @DisplayName("创建提交后 registry 更新失败必须硬删除记录并允许安全重试")
    void create_registry_failure_after_commit_restores_database_runtime_cache_and_alias(CapturedOutput output) {
        String providerId = "create-registry-compensation";
        List<String> providerIdsBefore = providerIds();
        String activeBefore = providerRegistry.active().id();
        AtomicReference<String> stagedSecretRef = captureSavedSecret(
                "provider." + providerId, "sk-create-registry-failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(providerRegistry).registerOrUpdate(argThat(config -> providerId.equals(config.id())));

        Throwable failure = catchThrowable(() -> providerSettingsService.create(
                apiKeyDraft(providerId, "sk-create-registry-failure", "model-before-retry")));

        assertSafeFailure(failure, "已恢复");
        assertThat(providerPersistenceService.findProvider(providerId)).isEmpty();
        assertThat(providerIds()).containsExactlyElementsOf(providerIdsBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        assertThat(stagedSecretRef.get()).isNotBlank();
        assertThat(secretStore.load(stagedSecretRef.get())).isEmpty();
        verify(chatClientFactory).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();

        doCallRealMethod().when(providerRegistry)
                .registerOrUpdate(argThat(config -> providerId.equals(config.id())));
        clearInvocations(chatClientFactory);

        ProviderSettingsService.ProviderView retried = providerSettingsService.create(
                apiKeyDraft(providerId, "sk-create-registry-retry", "model-after-retry"));

        ProviderConfigRecord savedAfterRetry = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(retried.id()).isEqualTo(providerId);
        assertThat(savedAfterRetry.model()).isEqualTo("model-after-retry");
        assertThat(providerRegistry.get(providerId).model()).isEqualTo("model-after-retry");
        assertThat(secretStore.load(savedAfterRetry.secretRef())).contains("sk-create-registry-retry");
        verify(chatClientFactory).invalidate(providerId);
    }

    @Test
    @DisplayName("更新提交后 ChatClient 失效失败必须恢复旧快照并允许安全重试")
    void update_invalidate_failure_after_commit_restores_database_runtime_cache_and_alias(CapturedOutput output) {
        String providerId = "update-invalidate-compensation";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-before-invalidate-failure", "model-before"));
        ProviderConfigRecord persistedBefore = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        String activeBefore = providerRegistry.active().id();
        clearInvocations(secretStore, providerRegistry, chatClientFactory);
        AtomicReference<String> failedStagedSecretRef = captureSavedSecret(
                "provider." + providerId, "sk-failed-invalidate-stage");
        AtomicInteger invalidateCalls = new AtomicInteger();
        doAnswer(invocation -> {
            if (invalidateCalls.incrementAndGet() == 1) {
                throw new IllegalStateException(SENSITIVE_MARKER);
            }
            return null;
        }).when(chatClientFactory).invalidate(providerId);

        Throwable failure = catchThrowable(() -> providerSettingsService.update(
                apiKeyDraft(providerId, "sk-failed-invalidate-stage", "model-should-rollback")));

        assertSafeFailure(failure, "已恢复");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(persistedBefore);
        assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        assertThat(secretStore.load(persistedBefore.secretRef())).contains("sk-before-invalidate-failure");
        assertThat(failedStagedSecretRef.get()).isNotBlank();
        assertThat(secretStore.load(failedStagedSecretRef.get())).isEmpty();
        verify(chatClientFactory, times(2)).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();

        ProviderSettingsService.ProviderView retried = providerSettingsService.update(
                apiKeyDraft(providerId, "sk-invalidate-retry", "model-after-retry"));

        ProviderConfigRecord savedAfterRetry = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(retried.id()).isEqualTo(providerId);
        assertThat(savedAfterRetry.model()).isEqualTo("model-after-retry");
        assertThat(providerRegistry.get(providerId).model()).isEqualTo("model-after-retry");
        assertThat(providerRegistry.get(providerId).apiKey()).isEqualTo("sk-invalidate-retry");
        assertThat(secretStore.load(persistedBefore.secretRef())).isEmpty();
        assertThat(secretStore.load(savedAfterRetry.secretRef())).contains("sk-invalidate-retry");
        verify(chatClientFactory, times(3)).invalidate(providerId);
    }

    @Test
    @DisplayName("轮换密钥后旧 alias 删除失败必须恢复数据库、运行时和密钥引用")
    void old_alias_cleanup_failure_rolls_back_committed_rotation(CapturedOutput output) {
        String providerId = "rotation-cleanup-rollback";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-before-cleanup-failure", "gpt-4o-mini"));
        ProviderConfigRecord persistedBefore = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        String activeBefore = providerRegistry.active().id();
        clearInvocations(secretStore, chatClientFactory);
        AtomicReference<String> stagedSecretRef = captureSavedSecret(
                "provider." + providerId, "sk-after-cleanup-failure");
        doAnswer(invocation -> {
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(secretStore).delete(persistedBefore.secretRef());

        Throwable failure = catchThrowable(() -> providerSettingsService.update(
                apiKeyDraft(providerId, "sk-after-cleanup-failure", "gpt-4.1-mini")));

        assertSafeFailure(failure, "已恢复原配置");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(persistedBefore);
        assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(activeBefore);
        assertThat(secretStore.load(persistedBefore.secretRef())).contains("sk-before-cleanup-failure");
        assertThat(stagedSecretRef.get()).isNotBlank();
        assertThat(secretStore.load(stagedSecretRef.get()).isPresent()).isFalse();
        verify(chatClientFactory, times(2)).invalidate(providerId);
        assertThat(output.getAll().contains(SENSITIVE_MARKER)).isFalse();
    }

    @Test
    @DisplayName("并发更新同一 Provider 时串行提交且清理中间 alias")
    void concurrent_updates_are_serialized_and_leave_database_registry_and_secret_consistent() throws Exception {
        String providerId = "serialized-provider-updates";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-before-concurrency", "gpt-4o-mini"));
        String originalSecretRef = providerPersistenceService.findProvider(providerId).orElseThrow().secretRef();
        clearInvocations(secretStore, chatClientFactory);

        AtomicReference<String> firstSecretRef = new AtomicReference<>();
        AtomicReference<String> secondSecretRef = new AtomicReference<>();
        CountDownLatch firstRequireEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRequire = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        CountDownLatch secondSecretStaged = new CountDownLatch(1);
        doAnswer(invocation -> {
            String secret = invocation.getArgument(1);
            String secretRef = (String) invocation.callRealMethod();
            if ("sk-concurrent-first".equals(secret)) {
                firstSecretRef.set(secretRef);
            } else if ("sk-concurrent-second".equals(secret)) {
                secondSecretRef.set(secretRef);
                secondSecretStaged.countDown();
            }
            return secretRef;
        }).when(secretStore).save(eq("provider." + providerId), anyString());
        doAnswer(invocation -> {
            String secretRef = invocation.getArgument(0);
            if (secretRef.equals(firstSecretRef.get())) {
                firstRequireEntered.countDown();
                if (!releaseFirstRequire.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待并发测试释放超时");
                }
            }
            return invocation.callRealMethod();
        }).when(secretStore).require(anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> providerSettingsService.update(
                    apiKeyDraft(providerId, "sk-concurrent-first", "model-first")));
            assertThat(firstRequireEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> {
                secondCallStarted.countDown();
                return providerSettingsService.update(
                        apiKeyDraft(providerId, "sk-concurrent-second", "model-second"));
            });
            assertThat(secondCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean secondStagedWhileFirstBlocked = secondSecretStaged.await(500, TimeUnit.MILLISECONDS);
            releaseFirstRequire.countDown();
            Throwable firstFailure = futureFailure(first);
            Throwable secondFailure = futureFailure(second);

            assertThat(secondStagedWhileFirstBlocked).isFalse();
            assertThat(firstFailure).isNull();
            assertThat(secondFailure).isNull();
        } finally {
            releaseFirstRequire.countDown();
            executor.shutdownNow();
        }

        ProviderConfigRecord saved = providerPersistenceService.findProvider(providerId).orElseThrow();
        assertThat(saved.model()).isEqualTo("model-second");
        assertThat(saved.secretRef()).isEqualTo(secondSecretRef.get());
        assertThat(providerRegistry.get(providerId).model()).isEqualTo("model-second");
        assertThat(providerRegistry.get(providerId).apiKey()).isEqualTo("sk-concurrent-second");
        assertThat(secretStore.load(originalSecretRef)).isEmpty();
        assertThat(secretStore.load(firstSecretRef.get())).isEmpty();
        assertThat(secretStore.load(secondSecretRef.get())).contains("sk-concurrent-second");
        verify(chatClientFactory, times(2)).invalidate(providerId);
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
        assertThat(result.message()).isEqualTo("Provider 配置检查失败");
        verify(anthropicOAuthCredentialSource).accessToken();
    }

    @Test
    @DisplayName("Provider 配置检查内部异常不得回显原始错误或密钥")
    void test_connection_failure_returns_fixed_safe_message(CapturedOutput output) {
        String providerId = "safe-provider-test-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-safe-provider-test", "gpt-4o-mini"));
        when(chatClientFactory.resolveChatModel(providerId))
                .thenThrow(new IllegalStateException(SENSITIVE_MARKER));

        ProviderSettingsService.ProviderTestResult result = providerSettingsService.testConnection(providerId);

        assertThat(result).isEqualTo(new ProviderSettingsService.ProviderTestResult(
                false, providerId, "Provider 配置检查失败"));
        assertThat(result.toString()).doesNotContain(SENSITIVE_MARKER);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
    }

    @Test
    @DisplayName("最后一个启用 Provider 不允许删除且所有状态保持不变")
    void deleting_last_enabled_provider_is_rejected_without_side_effects() {
        String providerId = "only-enabled-provider";
        ProviderConfigRecord record = ProviderConfigRecord.of(
                providerId, providerId, "OPENAI_COMPATIBLE", "api_key",
                "https://relay.example.com/v1", "gpt-4o-mini", "keystore://only", 64000, true,
                java.time.Instant.now());
        ProviderPersistenceService persistence = org.mockito.Mockito.mock(ProviderPersistenceService.class);
        AppSettingPersistenceService settings = org.mockito.Mockito.mock(AppSettingPersistenceService.class);
        SecretStore isolatedSecretStore = org.mockito.Mockito.mock(SecretStore.class);
        ModelProviderRegistry isolatedRegistry = registryWith(new ModelProviderConfig(
                providerId, providerId, ProviderType.OPENAI_COMPATIBLE, "gpt-4o-mini", "sk-only",
                "https://relay.example.com/v1", 64000));
        when(persistence.findProvider(providerId)).thenReturn(Optional.of(record));
        when(persistence.listProviders(true)).thenReturn(List.of(record));
        ProviderSettingsService isolatedService = newProviderSettingsService(
                persistence, settings, isolatedSecretStore, isolatedRegistry);

        Throwable failure = catchThrowable(() -> isolatedService.delete(providerId));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个启用 Provider");
        assertThat(isolatedRegistry.active().id()).isEqualTo(providerId);
        verify(persistence, never()).disableProvider(eq(providerId), org.mockito.ArgumentMatchers.any());
        verify(isolatedSecretStore, never()).delete(anyString());
        verify(chatClientFactory, never()).invalidate(providerId);
    }

    @Test
    @DisplayName("最后一个启用 Provider 也不得通过 update(enabled=false) 绕过删除不变量")
    void last_enabled_provider_cannot_be_disabled_through_update() {
        String providerId = "only-provider-update";
        ProviderConfigRecord record = ProviderConfigRecord.of(
                providerId, providerId, "OPENAI_COMPATIBLE", "api_key",
                "https://relay.example.com/v1", "gpt-4o-mini", "keystore://only-update", 64000, true,
                java.time.Instant.now());
        ProviderPersistenceService persistence = org.mockito.Mockito.mock(ProviderPersistenceService.class);
        AppSettingPersistenceService settings = org.mockito.Mockito.mock(AppSettingPersistenceService.class);
        SecretStore isolatedSecretStore = org.mockito.Mockito.mock(SecretStore.class);
        ModelProviderRegistry isolatedRegistry = registryWith(new ModelProviderConfig(
                providerId, providerId, ProviderType.OPENAI_COMPATIBLE, "gpt-4o-mini", "sk-only",
                "https://relay.example.com/v1", 64000));
        when(persistence.findProvider(providerId)).thenReturn(Optional.of(record));
        ProviderSettingsService isolatedService = newProviderSettingsService(
                persistence, settings, isolatedSecretStore, isolatedRegistry);
        ProviderSettingsService.ProviderDraft disabledDraft = new ProviderSettingsService.ProviderDraft(
                providerId, providerId, "OPENAI_COMPATIBLE", "api_key",
                "https://relay.example.com/v1", "gpt-4o-mini", "", 64000, false);

        Throwable failure = catchThrowable(() -> isolatedService.update(disabledDraft));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled");
        verify(persistence, never()).updateProvider(org.mockito.ArgumentMatchers.any());
        verify(isolatedSecretStore, never()).save(anyString(), anyString());
        assertThat(isolatedRegistry.active().id()).isEqualTo(providerId);
    }

    @Test
    @DisplayName("删除当前 Provider 时按 providerId 升序持久化 fallback 并清理旧密钥")
    void deleting_active_provider_persists_deterministic_fallback_and_returns_it() {
        String providerId = "zz-delete-current-provider";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-current", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        String expectedFallback = providerPersistenceService.listProviders(true).stream()
                .map(ProviderConfigRecord::providerId)
                .filter(id -> !id.equals(providerId))
                .sorted()
                .findFirst()
                .orElseThrow();
        clearInvocations(secretStore, chatClientFactory);

        ProviderSettingsService.ProviderDeleteResult result = providerSettingsService.delete(providerId);

        assertThat(result).isEqualTo(new ProviderSettingsService.ProviderDeleteResult(providerId, expectedFallback));
        assertThat(providerPersistenceService.findProvider(providerId)).get()
                .extracting(ProviderConfigRecord::enabled).isEqualTo(false);
        assertThat(appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)).get()
                .extracting(AppSettingRecord::settingValue).isEqualTo(expectedFallback);
        assertThat(providerRegistry.active().id()).isEqualTo(expectedFallback);
        assertThat(secretStore.load(before.secretRef())).isEmpty();
        verify(chatClientFactory).invalidate(providerId);
    }

    @Test
    @DisplayName("删除非当前 Provider 时保持 active 设置不变")
    void deleting_non_active_provider_keeps_active_provider() {
        String providerId = "zz-delete-non-active-provider";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-non-active", "gpt-4o-mini"));
        String activeProviderId = providerRegistry.list().stream()
                .map(ModelProviderConfig::id)
                .filter(id -> !id.equals(providerId))
                .sorted()
                .findFirst()
                .orElseThrow();
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(activeProviderId, null, null, null));

        ProviderSettingsService.ProviderDeleteResult result = providerSettingsService.delete(providerId);

        assertThat(result.activeProviderId()).isEqualTo(activeProviderId);
        assertThat(providerRegistry.active().id()).isEqualTo(activeProviderId);
        assertThat(appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)).get()
                .extracting(AppSettingRecord::settingValue).isEqualTo(activeProviderId);
    }

    @Test
    @DisplayName("删除与 set-active 并发时必须串行且 active 永远指向启用 Provider")
    void delete_and_set_active_are_serialized_on_shared_registry_lock() throws Exception {
        String providerId = "delete-set-active-race";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-set-active-race", "gpt-4o-mini"));
        String initialActive = providerRegistry.list().stream()
                .map(ModelProviderConfig::id)
                .filter(id -> !id.equals(providerId))
                .sorted()
                .findFirst()
                .orElseThrow();
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(initialActive, null, null, null));
        String secretRef = providerPersistenceService.findProvider(providerId).orElseThrow().secretRef();
        CountDownLatch deleteReachedPreCommit = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        CountDownLatch setActiveStarted = new CountDownLatch(1);
        CountDownLatch setActiveCompleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            deleteReachedPreCommit.countDown();
            if (!releaseDelete.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待删除竞态测试释放超时");
            }
            return invocation.callRealMethod();
        }).when(secretStore).require(secretRef);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleteFuture = executor.submit(() -> providerSettingsService.delete(providerId));
            assertThat(deleteReachedPreCommit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> setActiveFuture = executor.submit(() -> {
                setActiveStarted.countDown();
                try {
                    return appSettingsService.update(new AppSettingsService.AppSettingsUpdate(
                            providerId, null, null, null));
                } finally {
                    setActiveCompleted.countDown();
                }
            });
            assertThat(setActiveStarted.await(5, TimeUnit.SECONDS)).isTrue();
            boolean setActiveFinishedBeforeDelete = setActiveCompleted.await(500, TimeUnit.MILLISECONDS);
            releaseDelete.countDown();

            Throwable deleteFailure = futureFailure(deleteFuture);
            Throwable setActiveFailure = futureFailure(setActiveFuture);
            assertThat(setActiveFinishedBeforeDelete).isFalse();
            assertThat(deleteFailure).isNull();
            assertThat(setActiveFailure).isInstanceOf(IllegalArgumentException.class);
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }

        String persistedActive = appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)
                .map(AppSettingRecord::settingValue)
                .orElseThrow();
        assertThat(providerPersistenceService.findProvider(persistedActive)).get()
                .extracting(ProviderConfigRecord::enabled).isEqualTo(true);
        assertThat(providerRegistry.active().id()).isEqualTo(persistedActive);
    }

    @Test
    @DisplayName("首次 resolve 与删除并发时不得因 cache/registry 反向锁序死锁")
    void first_resolve_and_delete_complete_without_cache_registry_deadlock() throws Exception {
        String providerId = "deadlock-target";
        ModelProviderConfig target = new ModelProviderConfig(
                providerId, providerId, ProviderType.OPENAI_COMPATIBLE, "gpt-4o-mini", null,
                "https://relay.example.com/v1", 64000);
        ModelProviderConfig fallback = new ModelProviderConfig(
                "deadlock-fallback", "deadlock-fallback", ProviderType.OPENAI_COMPATIBLE,
                "gpt-4o-mini", null, "https://relay.example.com/v1", 64000);
        ModelProviderRegistry isolatedRegistry = registryWith(target, fallback);
        BaBiQProperties isolatedProperties = new BaBiQProperties(providerId, List.of(target, fallback), null);
        ProviderFactory providerFactory = org.mockito.Mockito.mock(ProviderFactory.class);
        when(providerFactory.supports()).thenReturn(ProviderType.OPENAI_COMPATIBLE);
        when(providerFactory.build(org.mockito.ArgumentMatchers.any())).thenReturn(
                org.mockito.Mockito.mock(ChatModel.class));
        CountDownLatch invalidateEntered = new CountDownLatch(1);
        CountDownLatch releaseInvalidate = new CountDownLatch(1);
        BlockingChatClientFactory blockingFactory = new BlockingChatClientFactory(
                isolatedRegistry, List.of(providerFactory), isolatedProperties,
                invalidateEntered, releaseInvalidate);
        ProviderPersistenceService persistence = org.mockito.Mockito.mock(ProviderPersistenceService.class);
        AppSettingPersistenceService settings = org.mockito.Mockito.mock(AppSettingPersistenceService.class);
        SecretStore secrets = org.mockito.Mockito.mock(SecretStore.class);
        ProviderConfigRecord targetRecord = ProviderConfigRecord.of(
                providerId, providerId, "OPENAI_COMPATIBLE", "api_key",
                "https://relay.example.com/v1", "gpt-4o-mini", null, 64000, true,
                java.time.Instant.now());
        ProviderConfigRecord fallbackRecord = ProviderConfigRecord.of(
                fallback.id(), fallback.displayName(), "OPENAI_COMPATIBLE", "api_key",
                fallback.baseUrl(), fallback.model(), null, 64000, true, java.time.Instant.now());
        when(persistence.findProvider(providerId)).thenReturn(Optional.of(targetRecord));
        when(persistence.listProviders(true)).thenReturn(List.of(targetRecord, fallbackRecord));
        when(settings.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)).thenReturn(Optional.of(
                new AppSettingRecord(AppSettingsService.KEY_ACTIVE_PROVIDER, providerId, "string",
                        java.time.Instant.now())));
        ProviderSettingsService isolatedService = newProviderSettingsService(
                persistence, settings, secrets, isolatedRegistry, blockingFactory);
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        AtomicReference<Throwable> resolveFailure = new AtomicReference<>();
        Thread deleteThread = daemonThread("provider-delete-deadlock", () -> {
            try {
                isolatedService.delete(providerId);
            } catch (Throwable failure) {
                deleteFailure.set(failure);
            }
        });
        Thread resolveThread = daemonThread("provider-resolve-deadlock", () -> {
            try {
                blockingFactory.resolve(providerId);
            } catch (Throwable failure) {
                resolveFailure.set(failure);
            }
        });

        deleteThread.start();
        assertThat(invalidateEntered.await(5, TimeUnit.SECONDS)).isTrue();
        resolveThread.start();
        await().atMost(Duration.ofSeconds(2)).until(() ->
                resolveThread.getState() == Thread.State.BLOCKED || !resolveThread.isAlive());
        releaseInvalidate.countDown();
        deleteThread.join(2_000);
        resolveThread.join(2_000);

        assertThat(deleteThread.isAlive()).isFalse();
        assertThat(resolveThread.isAlive()).isFalse();
        assertThat(deleteFailure.get()).isNull();
        assertThat(resolveFailure.get()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("删除 Provider 的 SQLite 失败必须保持数据库、active、运行时、缓存和密钥不变")
    void delete_database_failure_keeps_all_states_unchanged(CapturedOutput output) {
        String providerId = "delete-database-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-db-before", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        AppSettingRecord activeBefore = appSettingPersistenceService.findByKey(
                AppSettingsService.KEY_ACTIVE_PROVIDER).orElseThrow();
        clearInvocations(secretStore, chatClientFactory);
        installFailureTrigger("fail_provider_delete", "UPDATE", providerId);
        try {
            Throwable failure = catchThrowable(() -> providerSettingsService.delete(providerId));

            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(providerPersistenceService.findProvider(providerId)).contains(before);
            assertThat(appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER))
                    .contains(activeBefore);
            assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
            assertThat(providerRegistry.active().id()).isEqualTo(providerId);
            assertThat(secretStore.load(before.secretRef())).contains("sk-delete-db-before");
            verify(chatClientFactory, never()).invalidate(providerId);
            assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
        } finally {
            dropTrigger("fail_provider_delete");
        }
    }

    @Test
    @DisplayName("删除提交后 registry 失败必须恢复数据库、active、运行时、缓存和密钥")
    void delete_registry_failure_after_commit_restores_all_states(CapturedOutput output) {
        String providerId = "delete-registry-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-registry-before", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        ModelProviderConfig runtimeBefore = providerRegistry.get(providerId);
        clearInvocations(secretStore, providerRegistry, chatClientFactory);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(providerRegistry).disable(providerId);

        Throwable failure = catchThrowable(() -> providerSettingsService.delete(providerId));

        assertSafeFailure(failure, "已恢复原配置");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(before);
        assertThat(providerRegistry.get(providerId)).isEqualTo(runtimeBefore);
        assertThat(providerRegistry.active().id()).isEqualTo(providerId);
        assertThat(secretStore.load(before.secretRef())).contains("sk-delete-registry-before");
        verify(chatClientFactory).invalidate(providerId);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
    }

    @Test
    @DisplayName("删除后的运行时恢复持续失败时不得把数据库恢复成启用而运行时仍禁用")
    void delete_runtime_restore_failure_never_leaves_enabled_database_with_disabled_runtime(CapturedOutput output) {
        String providerId = "delete-runtime-restore-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-runtime-restore", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        String secretRef = providerPersistenceService.findProvider(providerId).orElseThrow().secretRef();
        clearInvocations(secretStore, providerRegistry, chatClientFactory);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(providerRegistry).disable(providerId);
        doAnswer(invocation -> {
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(providerRegistry).registerOrUpdate(argThat(config -> providerId.equals(config.id())));

        Throwable failure = catchThrowable(() -> providerSettingsService.delete(providerId));

        assertSafeFailure(failure, "恢复不完整");
        boolean databaseEnabled = providerPersistenceService.findProvider(providerId)
                .map(ProviderConfigRecord::enabled)
                .orElse(false);
        boolean runtimeEnabled;
        try {
            providerRegistry.get(providerId);
            runtimeEnabled = true;
        } catch (IllegalArgumentException ignored) {
            runtimeEnabled = false;
        }
        assertThat(databaseEnabled && !runtimeEnabled).isFalse();
        if (!databaseEnabled && !runtimeEnabled) {
            assertThat(secretStore.load(secretRef)).isEmpty();
        }
        String persistedActive = appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)
                .map(AppSettingRecord::settingValue)
                .orElseThrow();
        assertThat(providerRegistry.active().id()).isEqualTo(persistedActive);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
    }

    @Test
    @DisplayName("删除提交后 cache 失败必须恢复数据库、active、运行时和密钥")
    void delete_cache_failure_after_commit_restores_all_states(CapturedOutput output) {
        String providerId = "delete-cache-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-cache-before", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        AtomicInteger invalidations = new AtomicInteger();
        clearInvocations(secretStore, chatClientFactory);
        doAnswer(invocation -> {
            if (invalidations.incrementAndGet() == 1) {
                throw new IllegalStateException(SENSITIVE_MARKER);
            }
            return null;
        }).when(chatClientFactory).invalidate(providerId);

        Throwable failure = catchThrowable(() -> providerSettingsService.delete(providerId));

        assertSafeFailure(failure, "已恢复原配置");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(before);
        assertThat(providerRegistry.active().id()).isEqualTo(providerId);
        assertThat(secretStore.load(before.secretRef())).contains("sk-delete-cache-before");
        verify(chatClientFactory, times(2)).invalidate(providerId);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
    }

    @Test
    @DisplayName("删除旧 alias 失败必须恢复数据库、active、运行时和密钥引用")
    void delete_secret_cleanup_failure_restores_all_states(CapturedOutput output) {
        String providerId = "delete-secret-failure";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-delete-secret-before", "gpt-4o-mini"));
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(providerId, null, null, null));
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        clearInvocations(secretStore, chatClientFactory);
        doAnswer(invocation -> {
            throw new IllegalStateException(SENSITIVE_MARKER);
        }).when(secretStore).delete(before.secretRef());

        Throwable failure = catchThrowable(() -> providerSettingsService.delete(providerId));

        assertSafeFailure(failure, "已恢复原配置");
        assertThat(providerPersistenceService.findProvider(providerId)).contains(before);
        assertThat(providerRegistry.active().id()).isEqualTo(providerId);
        assertThat(secretStore.load(before.secretRef())).contains("sk-delete-secret-before");
        verify(chatClientFactory, times(2)).invalidate(providerId);
        assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
    }

    @Test
    @DisplayName("bootstrap 不得复活 SQLite 中已禁用的 YAML Provider")
    void bootstrap_does_not_resurrect_disabled_yaml_provider() {
        String providerId = baBiQProperties.providers().stream()
                .map(ModelProviderConfig::id)
                .filter(id -> !id.equals(baBiQProperties.activeProvider()))
                .findFirst()
                .orElseThrow();
        ProviderConfigRecord before = providerPersistenceService.findProvider(providerId).orElseThrow();
        Optional<AppSettingRecord> activeBefore = appSettingPersistenceService.findByKey(
                AppSettingsService.KEY_ACTIVE_PROVIDER);
        ProviderConfigRecord disabled = withEnabled(before, false);
        providerPersistenceService.updateProvider(disabled);
        appSettingPersistenceService.save(new AppSettingRecord(
                AppSettingsService.KEY_ACTIVE_PROVIDER, baBiQProperties.activeProvider(), "string",
                java.time.Instant.now()));
        ModelProviderRegistry freshRegistry = new ModelProviderRegistry(baBiQProperties);
        try {
            newProviderSettingsService(providerPersistenceService, appSettingPersistenceService,
                    secretStore, freshRegistry).bootstrap();

            assertThat(providerPersistenceService.findProvider(providerId)).contains(disabled);
            assertThatThrownBy(() -> freshRegistry.get(providerId))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            providerPersistenceService.updateProvider(before);
            restoreActiveSetting(activeBefore);
        }
    }

    @Test
    @DisplayName("bootstrap 以 SQLite 配置覆盖 YAML 并恢复持久化 active Provider")
    void bootstrap_uses_sqlite_snapshot_and_restores_persisted_active_provider() {
        String providerId = "bootstrap-persisted-active";
        providerSettingsService.create(apiKeyDraft(providerId, "sk-bootstrap-active", "sqlite-model"));
        String yamlProviderId = baBiQProperties.activeProvider();
        ProviderConfigRecord yamlBefore = providerPersistenceService.findProvider(yamlProviderId).orElseThrow();
        ProviderConfigRecord sqliteOverride = new ProviderConfigRecord(
                yamlBefore.providerId(), yamlBefore.displayName(), yamlBefore.type(), yamlBefore.authMode(),
                yamlBefore.baseUrl(), "sqlite-overrides-yaml", yamlBefore.secretRef(), yamlBefore.contextWindow(),
                true, yamlBefore.createdAt(), java.time.Instant.now());
        providerPersistenceService.updateProvider(sqliteOverride);
        Optional<AppSettingRecord> activeBefore = appSettingPersistenceService.findByKey(
                AppSettingsService.KEY_ACTIVE_PROVIDER);
        appSettingPersistenceService.save(new AppSettingRecord(
                AppSettingsService.KEY_ACTIVE_PROVIDER, providerId, "string", java.time.Instant.now()));
        ModelProviderRegistry freshRegistry = new ModelProviderRegistry(baBiQProperties);
        try {
            newProviderSettingsService(providerPersistenceService, appSettingPersistenceService,
                    secretStore, freshRegistry).bootstrap();

            assertThat(freshRegistry.active().id()).isEqualTo(providerId);
            assertThat(freshRegistry.get(providerId).model()).isEqualTo("sqlite-model");
            assertThat(freshRegistry.get(yamlProviderId).model()).isEqualTo("sqlite-overrides-yaml");
        } finally {
            providerPersistenceService.updateProvider(yamlBefore);
            restoreActiveSetting(activeBefore);
        }
    }

    @Test
    @DisplayName("bootstrap 自动把无效 active 修复为 providerId 升序首个启用 Provider 并持久化")
    void bootstrap_repairs_invalid_active_provider_deterministically() {
        Optional<AppSettingRecord> activeBefore = appSettingPersistenceService.findByKey(
                AppSettingsService.KEY_ACTIVE_PROVIDER);
        appSettingPersistenceService.save(new AppSettingRecord(
                AppSettingsService.KEY_ACTIVE_PROVIDER, "missing-bootstrap-active", "string",
                java.time.Instant.now()));
        String expected = providerPersistenceService.listProviders(true).stream()
                .map(ProviderConfigRecord::providerId)
                .sorted()
                .findFirst()
                .orElseThrow();
        ModelProviderRegistry freshRegistry = new ModelProviderRegistry(baBiQProperties);
        try {
            newProviderSettingsService(providerPersistenceService, appSettingPersistenceService,
                    secretStore, freshRegistry).bootstrap();

            assertThat(freshRegistry.active().id()).isEqualTo(expected);
            assertThat(appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)).get()
                    .extracting(AppSettingRecord::settingValue).isEqualTo(expected);
        } finally {
            restoreActiveSetting(activeBefore);
        }
    }

    @Test
    @DisplayName("bootstrap 把空白 active setting 修复为 providerId 升序首个启用 Provider 并持久化")
    void bootstrap_repairs_blank_active_provider_setting() {
        Optional<AppSettingRecord> activeBefore = appSettingPersistenceService.findByKey(
                AppSettingsService.KEY_ACTIVE_PROVIDER);
        appSettingPersistenceService.save(new AppSettingRecord(
                AppSettingsService.KEY_ACTIVE_PROVIDER, "   ", "string", java.time.Instant.now()));
        String expected = providerPersistenceService.listProviders(true).stream()
                .map(ProviderConfigRecord::providerId)
                .sorted()
                .findFirst()
                .orElseThrow();
        ModelProviderRegistry freshRegistry = new ModelProviderRegistry(baBiQProperties);
        try {
            newProviderSettingsService(providerPersistenceService, appSettingPersistenceService,
                    secretStore, freshRegistry).bootstrap();

            assertThat(freshRegistry.active().id()).isEqualTo(expected);
            assertThat(appSettingPersistenceService.findByKey(AppSettingsService.KEY_ACTIVE_PROVIDER)).get()
                    .extracting(AppSettingRecord::settingValue).isEqualTo(expected);
        } finally {
            restoreActiveSetting(activeBefore);
        }
    }

    @Test
    @DisplayName("bootstrap 新 YAML Provider 的 SQLite 失败必须补偿删除新 secret alias")
    void bootstrap_database_failure_compensates_new_yaml_secret_alias(CapturedOutput output) {
        String providerId = "bootstrap-new-yaml-failure";
        ModelProviderConfig yamlProvider = new ModelProviderConfig(
                providerId, providerId, ProviderType.OPENAI_COMPATIBLE, "gpt-4o-mini", SENSITIVE_MARKER,
                "https://relay.example.com/v1", 64000);
        ModelProviderRegistry freshRegistry = registryWith(yamlProvider);
        AtomicReference<String> stagedSecretRef = captureSavedSecret("provider." + providerId, SENSITIVE_MARKER);
        installFailureTrigger("fail_bootstrap_provider_insert", "INSERT", providerId);
        try {
            Throwable failure = catchThrowable(() -> newProviderSettingsService(
                    providerPersistenceService, appSettingPersistenceService, secretStore, freshRegistry).bootstrap());

            assertThat(failure).isInstanceOf(RuntimeException.class);
            assertThat(providerPersistenceService.findProvider(providerId)).isEmpty();
            assertThat(stagedSecretRef.get()).isNotBlank();
            assertThat(secretStore.load(stagedSecretRef.get())).isEmpty();
            assertThat(output.getAll()).doesNotContain(SENSITIVE_MARKER);
        } finally {
            dropTrigger("fail_bootstrap_provider_insert");
        }
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

    private ProviderSettingsService newProviderSettingsService(ProviderPersistenceService persistence,
                                                                AppSettingPersistenceService settings,
                                                                SecretStore secrets,
                                                                ModelProviderRegistry targetRegistry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClientFactory> chatClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(chatClientFactory);
        when(chatClientProvider.getObject()).thenReturn(chatClientFactory);
        @SuppressWarnings("unchecked")
        ObjectProvider<AnthropicOAuthCredentialSource> oauthProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(oauthProvider.getObject()).thenReturn(anthropicOAuthCredentialSource);
        return new ProviderSettingsService(
                persistence, settings, new ProviderMutationCoordinator(), secrets, targetRegistry,
                chatClientProvider, oauthProvider, transactionManager);
    }

    private ProviderSettingsService newProviderSettingsService(ProviderPersistenceService persistence,
                                                                AppSettingPersistenceService settings,
                                                                SecretStore secrets,
                                                                ModelProviderRegistry targetRegistry,
                                                                ChatClientFactory targetChatClientFactory) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClientFactory> chatClientProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(chatClientProvider.getIfAvailable()).thenReturn(targetChatClientFactory);
        when(chatClientProvider.getObject()).thenReturn(targetChatClientFactory);
        @SuppressWarnings("unchecked")
        ObjectProvider<AnthropicOAuthCredentialSource> oauthProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(oauthProvider.getObject()).thenReturn(anthropicOAuthCredentialSource);
        return new ProviderSettingsService(
                persistence, settings, new ProviderMutationCoordinator(), secrets, targetRegistry,
                chatClientProvider, oauthProvider, transactionManager);
    }

    private static Thread daemonThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static ModelProviderRegistry registryWith(ModelProviderConfig... providers) {
        return new ModelProviderRegistry(new BaBiQProperties(providers[0].id(), List.of(providers), null));
    }

    private static ProviderConfigRecord withEnabled(ProviderConfigRecord record, boolean enabled) {
        return new ProviderConfigRecord(
                record.providerId(), record.displayName(), record.type(), record.authMode(), record.baseUrl(),
                record.model(), record.secretRef(), record.contextWindow(), enabled,
                record.createdAt(), java.time.Instant.now());
    }

    private void restoreActiveSetting(Optional<AppSettingRecord> previous) {
        if (previous.isPresent()) {
            appSettingPersistenceService.save(previous.orElseThrow());
            return;
        }
        jdbcTemplate.update("DELETE FROM bq_app_settings WHERE setting_key = ?", AppSettingsService.KEY_ACTIVE_PROVIDER);
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

    /** 断言对外失败信息和 suppressed 补偿错误都不包含敏感标记。 */
    private static void assertSafeFailure(Throwable failure, String messagePart) {
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
        assertThat(failure.toString().contains(SENSITIVE_MARKER)).isFalse();
        assertThat(Arrays.stream(failure.getSuppressed())
                .noneMatch(suppressed -> suppressed.toString().contains(SENSITIVE_MARKER))).isTrue();
    }

    /** 等待并发调用结束并把执行异常转换成可断言的结果。 */
    private static Throwable futureFailure(Future<?> future) throws InterruptedException {
        try {
            future.get(5, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (java.util.concurrent.TimeoutException exception) {
            return exception;
        }
    }

    /** 在 invalidate 入口暂停，供测试精确构造 cache/registry 反向锁序。 */
    private static final class BlockingChatClientFactory extends ChatClientFactory {

        private final CountDownLatch invalidateEntered;
        private final CountDownLatch releaseInvalidate;

        private BlockingChatClientFactory(ModelProviderRegistry registry,
                                          List<ProviderFactory> factories,
                                          BaBiQProperties properties,
                                          CountDownLatch invalidateEntered,
                                          CountDownLatch releaseInvalidate) {
            super(registry, factories, properties);
            this.invalidateEntered = invalidateEntered;
            this.releaseInvalidate = releaseInvalidate;
        }

        @Override
        public void invalidate(String providerId) {
            invalidateEntered.countDown();
            try {
                if (!releaseInvalidate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待 deadlock 测试释放超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("deadlock 测试被中断");
            }
            super.invalidate(providerId);
        }
    }
}
