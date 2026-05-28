package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
