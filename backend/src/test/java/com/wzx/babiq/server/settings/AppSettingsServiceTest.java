package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.persistence.service.AppSettingPersistenceService;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 应用设置服务测试。
 *
 * <p>P2-3 的 Provider、沙箱和审批策略都从这里读取“下一轮 turn 的默认值”。
 * 已经启动的 turn 会把这些值写入自身快照，因此设置修改不能倒灌到运行中 turn。</p>
 */
@SpringBootTest
class AppSettingsServiceTest {

    /** 独立 SQLite 文件，确保设置读写测试不污染真实用户设置。 */
    private static final Path TEST_DB = Path.of("target", "test-db",
            "app-settings-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void settingsProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private AppSettingsService appSettingsService;
    @Autowired
    private AgentLoopProperties agentLoopProperties;
    @Autowired
    private ModelProviderRegistry providerRegistry;
    @MockitoSpyBean
    private AppSettingPersistenceService appSettingPersistenceService;

    /**
     * 每个测试都把运行时 active 恢复为稳定基线，避免前一用例的切换影响时序断言。
     */
    @BeforeEach
    void resetActiveProvider() {
        providerRegistry.setActive("dashscope-default");
    }

    @Test
    @DisplayName("缺失设置时返回 application.yml 和 AgentLoopProperties 的默认快照")
    void get_should_return_defaults_when_settings_are_missing() {
        AppSettings settings = appSettingsService.get();

        assertThat(settings.activeProviderId()).isNotBlank();
        assertThat(settings.sandboxMode()).isEqualTo(agentLoopProperties.sandboxMode().name());
        assertThat(settings.approvalPolicy()).isEqualTo(agentLoopProperties.approvalPolicy().name());
        assertThat(settings.defaultCwd()).isNotBlank();
    }

    @Test
    @DisplayName("更新沙箱、审批和默认工作目录后能再次读取")
    void update_should_persist_typed_settings() {
        appSettingsService.update(new AppSettingsService.AppSettingsUpdate(
                "deepseek-official",
                SandboxMode.READ_ONLY.name(),
                ApprovalPolicy.ALWAYS.name(),
                "D:\\Work"));

        AppSettings settings = appSettingsService.get();

        assertThat(settings.activeProviderId()).isEqualTo("deepseek-official");
        assertThat(settings.sandboxMode()).isEqualTo("READ_ONLY");
        assertThat(settings.approvalPolicy()).isEqualTo("ALWAYS");
        assertThat(settings.defaultCwd()).isEqualTo("D:\\Work");
    }

    @Test
    @DisplayName("非法沙箱模式和审批策略会被拒绝")
    void update_should_reject_unknown_enum_values() {
        assertThatThrownBy(() -> appSettingsService.update(new AppSettingsService.AppSettingsUpdate(
                null,
                "FULL_DISK",
                "ON_REQUEST",
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sandboxMode");

        assertThatThrownBy(() -> appSettingsService.update(new AppSettingsService.AppSettingsUpdate(
                null,
                "WORKSPACE_WRITE",
                "PROMPT_ME",
                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalPolicy");
    }

    @Test
    @DisplayName("设置持久化失败时不得改变运行时 active Provider")
    void failed_setting_persistence_must_not_change_runtime_active_provider() {
        doThrow(new IllegalStateException("db-failed"))
                .when(appSettingPersistenceService).save(any());

        assertThatThrownBy(() -> appSettingsService.update(activeProvider("deepseek-official")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db-failed");

        assertThat(providerRegistry.active().id()).isEqualTo("dashscope-default");
    }

    @Test
    @DisplayName("设置成功时事务提交后才切换运行时 active Provider")
    void successful_setting_update_changes_registry_only_after_transaction_commit() {
        AtomicBoolean synchronizationRegistered = new AtomicBoolean();
        AtomicBoolean commitObserved = new AtomicBoolean();
        doAnswer(invocation -> {
            assertThat(providerRegistry.active().id()).isEqualTo("dashscope-default");
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (synchronizationRegistered.compareAndSet(false, true)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        assertThat(providerRegistry.active().id()).isEqualTo("dashscope-default");
                        commitObserved.set(true);
                    }
                });
            }
            return invocation.callRealMethod();
        }).when(appSettingPersistenceService).save(any());

        AppSettings settings = appSettingsService.update(activeProvider("deepseek-official"));

        assertThat(commitObserved).isTrue();
        assertThat(settings.activeProviderId()).isEqualTo("deepseek-official");
        assertThat(providerRegistry.active().id()).isEqualTo("deepseek-official");
    }

    /** 构造只修改 active Provider 的部分更新请求。 */
    private static AppSettingsService.AppSettingsUpdate activeProvider(String providerId) {
        return new AppSettingsService.AppSettingsUpdate(providerId, null, null, null);
    }
}
