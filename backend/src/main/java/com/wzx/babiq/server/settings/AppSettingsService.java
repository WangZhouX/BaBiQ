package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.agent.AgentLoopProperties;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.repository.AppSettingRecord;
import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.persistence.service.AppSettingPersistenceService;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 应用设置服务。
 *
 * <p>它把 `bq_app_settings` 里的 key/value 字符串包装成类型化设置，并负责枚举校验。
 * UI 和 JSON-RPC handler 都不直接读写裸 key，避免不同入口写出不一致的设置值。</p>
 */
@Service
public class AppSettingsService {

    /** 当前激活 Provider 的设置 key。 */
    public static final String KEY_ACTIVE_PROVIDER = "active_provider_id";
    /** 默认沙箱模式的设置 key。 */
    public static final String KEY_SANDBOX_MODE = "sandbox.mode";
    /** 默认审批策略的设置 key。 */
    public static final String KEY_APPROVAL_POLICY = "approval.policy";
    /** 新建会话默认工作目录的设置 key。 */
    public static final String KEY_DEFAULT_CWD = "default.cwd";

    /** key/value 持久化服务。 */
    private final AppSettingPersistenceService appSettingPersistenceService;
    /** yml 根配置，提供 active provider 的默认值。 */
    private final BaBiQProperties baBiQProperties;
    /** AgentLoopProperties 提供沙箱和审批策略默认值。 */
    private final AgentLoopProperties agentLoopProperties;
    /** Provider 注册表；activeProviderId 更新后同步这里，下一轮 turn 立即生效。 */
    private final ModelProviderRegistry providerRegistry;

    /**
     * 创建应用设置服务。
     *
     * @param appSettingPersistenceService 设置持久化服务
     * @param baBiQProperties BaBiQ 根配置
     * @param agentLoopProperties Agent Loop 默认配置
     * @param providerRegistry Provider 注册表
     */
    public AppSettingsService(AppSettingPersistenceService appSettingPersistenceService,
                              BaBiQProperties baBiQProperties,
                              AgentLoopProperties agentLoopProperties,
                              ModelProviderRegistry providerRegistry) {
        this.appSettingPersistenceService = appSettingPersistenceService;
        this.baBiQProperties = baBiQProperties;
        this.agentLoopProperties = agentLoopProperties;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 读取当前设置，缺失项用 yml 默认值补齐。
     *
     * @return 当前应用设置快照
     */
    public AppSettings get() {
        return new AppSettings(
                valueOrDefault(KEY_ACTIVE_PROVIDER, baBiQProperties.activeProvider()),
                valueOrDefault(KEY_SANDBOX_MODE, agentLoopProperties.sandboxMode().name()),
                valueOrDefault(KEY_APPROVAL_POLICY, agentLoopProperties.approvalPolicy().name()),
                valueOrDefault(KEY_DEFAULT_CWD, Path.of("").toAbsolutePath().normalize().toString()));
    }

    /**
     * 部分更新应用设置。
     *
     * @param update 更新请求；字段为 null 时保留原值
     * @return 更新后的设置快照
     */
    @Transactional
    public AppSettings update(AppSettingsUpdate update) {
        AppSettings current = get();
        String activeProviderId = choose(update.activeProviderId(), current.activeProviderId());
        String sandboxMode = choose(update.sandboxMode(), current.sandboxMode());
        String approvalPolicy = choose(update.approvalPolicy(), current.approvalPolicy());
        String defaultCwd = choose(update.defaultCwd(), current.defaultCwd());

        providerRegistry.setActive(activeProviderId);
        validateSandboxMode(sandboxMode);
        validateApprovalPolicy(approvalPolicy);

        Instant now = Instant.now();
        save(KEY_ACTIVE_PROVIDER, activeProviderId, now);
        save(KEY_SANDBOX_MODE, sandboxMode, now);
        save(KEY_APPROVAL_POLICY, approvalPolicy, now);
        save(KEY_DEFAULT_CWD, defaultCwd, now);
        return get();
    }

    private String valueOrDefault(String key, String defaultValue) {
        return appSettingPersistenceService.findByKey(key)
                .map(AppSettingRecord::settingValue)
                .filter(value -> !value.isBlank())
                .orElse(defaultValue);
    }

    private void save(String key, String value, Instant now) {
        appSettingPersistenceService.save(new AppSettingRecord(key, value, "string", now));
    }

    private static String choose(String requested, String current) {
        return requested == null || requested.isBlank() ? current : requested;
    }

    private static void validateSandboxMode(String sandboxMode) {
        try {
            SandboxMode.valueOf(sandboxMode);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知 sandboxMode: " + sandboxMode, exception);
        }
    }

    private static void validateApprovalPolicy(String approvalPolicy) {
        try {
            ApprovalPolicy.valueOf(approvalPolicy);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知 approvalPolicy: " + approvalPolicy, exception);
        }
    }

    /**
     * 应用设置更新请求。
     *
     * @param activeProviderId 新默认 Provider；为空时保留
     * @param sandboxMode 新默认沙箱模式；为空时保留
     * @param approvalPolicy 新默认审批策略；为空时保留
     * @param defaultCwd 新默认工作目录；为空时保留
     */
    public record AppSettingsUpdate(
            String activeProviderId,
            String sandboxMode,
            String approvalPolicy,
            String defaultCwd
    ) {
    }
}
