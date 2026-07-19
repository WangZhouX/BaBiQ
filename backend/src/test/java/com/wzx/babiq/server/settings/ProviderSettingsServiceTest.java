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
import java.util.Arrays;
import java.util.List;
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
import static org.assertj.core.api.Assertions.catchThrowable;
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
}
