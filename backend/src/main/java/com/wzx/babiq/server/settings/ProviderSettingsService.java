package com.wzx.babiq.server.settings;

import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.model.ChatClientFactory;
import com.wzx.babiq.server.model.ModelMetadata;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.model.ProviderType;
import com.wzx.babiq.server.persistence.service.ProviderPersistenceService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Provider 设置应用服务。
 *
 * <p>桌面端新增、编辑、删除 Provider 时只调用这个服务。它负责三件事：
 * 校验 Provider 参数、把 API Key 写入 SecretStore、把非敏感配置写入 SQLite 并同步运行期注册表。</p>
 */
@Service
public class ProviderSettingsService {

    /** Provider 配置持久化服务，只保存 secretRef。 */
    private final ProviderPersistenceService providerPersistenceService;
    /** 本地密钥存储，保存 API Key 明文。 */
    private final SecretStore secretStore;
    /** 运行期模型 Provider 注册表，下一轮 turn 会从这里读取最新配置。 */
    private final ModelProviderRegistry registry;
    /** ChatClient 缓存工厂，Provider 更新后需要清理旧客户端。 */
    private final ObjectProvider<ChatClientFactory> chatClientFactoryProvider;

    /**
     * 创建 Provider 设置服务。
     *
     * @param providerPersistenceService Provider 配置持久化服务
     * @param secretStore 本地密钥存储
     * @param registry 运行期 Provider 注册表
     * @param chatClientFactoryProvider ChatClient 工厂懒加载引用，避免启动期循环依赖
     */
    public ProviderSettingsService(ProviderPersistenceService providerPersistenceService,
                                   SecretStore secretStore,
                                   ModelProviderRegistry registry,
                                   ObjectProvider<ChatClientFactory> chatClientFactoryProvider) {
        this.providerPersistenceService = providerPersistenceService;
        this.secretStore = secretStore;
        this.registry = registry;
        this.chatClientFactoryProvider = chatClientFactoryProvider;
    }

    /**
     * 把 `application.yml` 中的初始 Provider 同步到 SQLite。
     *
     * <p>这样 P2-3 之后，设置页看到的是统一的本地数据库真相源；如果用户创建了新 Provider，
     * 服务重启后也会重新注册到运行期 registry。</p>
     */
    @PostConstruct
    @Transactional
    public void bootstrap() {
        Instant now = Instant.now();
        for (ModelProviderConfig config : registry.list()) {
            if (providerPersistenceService.findProvider(config.id()).isEmpty()) {
                String secretRef = config.apiKey() == null || config.apiKey().isBlank()
                        ? null
                        : secretStore.save("provider." + config.id(), config.apiKey());
                providerPersistenceService.saveProvider(ProviderConfigRecord.of(
                        config.id(),
                        config.displayName(),
                        config.type().name(),
                        persistenceBaseUrl(config.baseUrl()),
                        config.model(),
                        secretRef,
                        effectiveContextWindow(config.model(), config.contextWindow() == null ? 0 : config.contextWindow()),
                        true,
                        now));
            }
        }
        for (ProviderConfigRecord record : providerPersistenceService.listProviders(true)) {
            registry.registerOrUpdate(toRuntimeConfig(record));
        }
    }

    /**
     * 创建 Provider。
     *
     * @param draft 桌面端提交的 Provider 草稿
     * @return 非敏感 Provider 视图
     */
    @Transactional
    public ProviderView create(ProviderDraft draft) {
        validateRequired(draft, true);
        Instant now = Instant.now();
        String secretRef = secretStore.save("provider." + draft.providerId(), draft.apiKey());
        ProviderConfigRecord record = ProviderConfigRecord.of(
                draft.providerId(),
                draft.displayName(),
                ProviderType.valueOf(draft.type()).name(),
                draft.baseUrl(),
                draft.model(),
                secretRef,
                effectiveContextWindow(draft.model(), draft.contextWindow()),
                draft.enabled(),
                now);
        providerPersistenceService.saveProvider(record);
        registry.registerOrUpdate(toRuntimeConfig(record));
        invalidateClient(record.providerId());
        return toView(record, true);
    }

    /**
     * 更新 Provider；API Key 为空时沿用原 secretRef。
     *
     * @param draft Provider 草稿
     * @return 更新后的非敏感视图
     */
    @Transactional
    public ProviderView update(ProviderDraft draft) {
        validateRequired(draft, false);
        ProviderConfigRecord existing = providerPersistenceService.findProvider(draft.providerId())
                .orElseThrow(() -> new IllegalArgumentException("Provider 不存在: " + draft.providerId()));
        String secretRef = draft.apiKey() == null || draft.apiKey().isBlank()
                ? existing.secretRef()
                : secretStore.save("provider." + draft.providerId(), draft.apiKey());
        ProviderConfigRecord record = new ProviderConfigRecord(
                draft.providerId(),
                draft.displayName(),
                ProviderType.valueOf(draft.type()).name(),
                draft.baseUrl(),
                draft.model(),
                secretRef,
                effectiveContextWindow(draft.model(), draft.contextWindow()),
                draft.enabled(),
                existing.createdAt(),
                Instant.now());
        providerPersistenceService.saveProvider(record);
        if (record.enabled()) {
            registry.registerOrUpdate(toRuntimeConfig(record));
        } else {
            registry.disable(record.providerId());
        }
        invalidateClient(record.providerId());
        return toView(record, record.secretRef() != null);
    }

    /**
     * 软删除 Provider。
     *
     * @param providerId Provider 标识
     */
    @Transactional
    public void delete(String providerId) {
        requireText(providerId, "providerId");
        providerPersistenceService.disableProvider(providerId, Instant.now());
        registry.disable(providerId);
        invalidateClient(providerId);
    }

    /**
     * 返回所有启用 Provider 的非敏感视图。
     */
    public List<ProviderView> listEnabled() {
        String activeProviderId = registry.active().id();
        return providerPersistenceService.listProviders(true).stream()
                .map(record -> toView(record, record.providerId().equals(activeProviderId)))
                .toList();
    }

    /**
     * 做轻量 Provider 配置检查。
     *
     * <p>P2-3 不在测试连接时真实扣费调用模型，只检查配置能被 ChatClientFactory 构造成 ChatModel。
     * 真正的模型调用仍发生在下一轮 turn。</p>
     *
     * @param providerId Provider 标识
     * @return 测试结果
     */
    public ProviderTestResult testConnection(String providerId) {
        try {
            chatClientFactoryProvider.getIfAvailable().resolveChatModel(providerId);
            return new ProviderTestResult(true, providerId, "Provider 配置可用");
        } catch (Exception exception) {
            return new ProviderTestResult(false, providerId, exception.getMessage());
        }
    }

    private ModelProviderConfig toRuntimeConfig(ProviderConfigRecord record) {
        String apiKey = record.secretRef() == null ? null : secretStore.require(record.secretRef());
        return new ModelProviderConfig(
                record.providerId(),
                record.displayName(),
                ProviderType.valueOf(record.type()),
                record.model(),
                apiKey,
                runtimeBaseUrl(record.baseUrl()),
                effectiveContextWindow(record.model(), record.contextWindow()));
    }

    private ProviderView toView(ProviderConfigRecord record, boolean active) {
        return new ProviderView(
                record.providerId(),
                record.displayName(),
                record.type(),
                record.baseUrl(),
                record.model(),
                effectiveContextWindow(record.model(), record.contextWindow()),
                record.enabled(),
                record.secretRef() != null && !record.secretRef().isBlank(),
                active,
                null);
    }

    private void invalidateClient(String providerId) {
        ChatClientFactory chatClientFactory = chatClientFactoryProvider.getIfAvailable();
        if (chatClientFactory != null) {
            chatClientFactory.invalidate(providerId);
        }
    }

    private static void validateRequired(ProviderDraft draft, boolean requireApiKey) {
        requireText(draft.providerId(), "providerId");
        requireText(draft.displayName(), "displayName");
        requireText(draft.type(), "type");
        requireText(draft.baseUrl(), "baseUrl");
        requireText(draft.model(), "model");
        ProviderType.valueOf(draft.type());
        if (requireApiKey) {
            requireText(draft.apiKey(), "apiKey");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + fieldName);
        }
    }

    /**
     * 把运行期允许为空的 baseUrl 转成数据库可保存的空字符串。
     *
     * <p>DashScope 这类官方 SDK Provider 不需要 OpenAI 兼容 baseUrl；但 P2-1 的 Provider 表为了简化索引和展示，
     * 字段定义为 NOT NULL，所以这里把“没有 baseUrl”规范化为空字符串，避免启动同步配置时失败。</p>
     *
     * @param baseUrl 运行期 Provider 配置里的 baseUrl，可能为 null
     * @return 可写入 SQLite 的 baseUrl 字符串
     */
    private static String persistenceBaseUrl(String baseUrl) {
        return baseUrl == null ? "" : baseUrl;
    }

    /**
     * 把数据库里的空字符串还原成运行期 null。
     *
     * <p>这样 OpenAI 兼容 Provider 仍然会在工厂层校验 baseUrl，而 DashScope Provider 可以继续表达“没有这个字段”。</p>
     *
     * @param baseUrl SQLite 中保存的 baseUrl，空字符串表示未配置
     * @return 运行期 Provider 配置使用的 baseUrl
     */
    private static String runtimeBaseUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
    }

    /**
     * 计算最终展示和运行使用的上下文窗口。
     *
     * <p>旧数据或表单草稿可能把 0 当作“未手动填写”，这里统一回退到模型元数据，避免 UI 或测试看到 0
     * 这种没有业务意义的窗口大小。</p>
     *
     * @param model 模型名
     * @param configured 用户或 YAML 显式配置的窗口大小，0 表示自动推导
     * @return 大于 0 的有效上下文窗口
     */
    private static int effectiveContextWindow(String model, int configured) {
        return configured > 0 ? configured : ModelMetadata.contextWindowOf(model);
    }

    /**
     * Provider 编辑草稿。
     *
     * @param providerId Provider 稳定标识
     * @param displayName 展示名称
     * @param type Provider 类型
     * @param baseUrl API Base URL
     * @param model 默认模型
     * @param apiKey 明文 API Key，只允许从桌面端进入服务层，不能写入数据库
     * @param contextWindow 上下文窗口大小
     * @param enabled 是否启用
     */
    public record ProviderDraft(
            String providerId,
            String displayName,
            String type,
            String baseUrl,
            String model,
            String apiKey,
            int contextWindow,
            boolean enabled
    ) {
    }

    /**
     * Provider 非敏感视图。
     *
     * @param id Provider 标识
     * @param displayName 展示名称
     * @param type Provider 类型
     * @param baseUrl API Base URL
     * @param model 默认模型
     * @param contextWindow 上下文窗口大小
     * @param enabled 是否启用
     * @param hasApiKey 是否已经保存 API Key
     * @param active 是否为当前激活 Provider
     * @param apiKey 永远为空；保留字段只用于测试和未来表单草稿，不参与 JSON 响应
     */
    public record ProviderView(
            String id,
            String displayName,
            String type,
            String baseUrl,
            String model,
            int contextWindow,
            boolean enabled,
            boolean hasApiKey,
            boolean active,
            String apiKey
    ) {
    }

    /**
     * Provider 测试连接结果。
     *
     * @param ok 是否通过轻量检查
     * @param providerId Provider 标识
     * @param message 可展示给用户的结果说明
     */
    public record ProviderTestResult(boolean ok, String providerId, String message) {
    }
}
