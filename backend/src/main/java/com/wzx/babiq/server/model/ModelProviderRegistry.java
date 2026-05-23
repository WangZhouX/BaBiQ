package com.wzx.babiq.server.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型 Provider 配置注册中心。
 *
 * <p>该组件把 {@link BaBiQProperties#providers()} 转换为按 id 查询的只读索引,
 * 并在启动期校验 providers 非空、id 不重复、active-provider 必须存在。
 * 运行期只允许切换 active id,不允许动态增删 provider,避免配置源和内存状态分叉。</p>
 */
@Component
public class ModelProviderRegistry {

    /** providerId -> Provider 配置，供后端校验 UI 选择的模型是否存在。 */
    private final Map<String, ModelProviderConfig> providersById;
    /** 当前 UI 选中的 provider；AtomicReference 让切换操作在并发请求下保持原子可见。 */
    private final AtomicReference<String> activeProviderId;

    /**
     * 根据 BaBiQ 根配置初始化注册中心。
     *
     * @param properties 从 application.yml 绑定出的 Provider 配置
     * @throws IllegalStateException providers 为空、id 重复或 active-provider 不存在时抛出
     */
    public ModelProviderRegistry(BaBiQProperties properties) {
        Map<String, ModelProviderConfig> checkedProviders = indexProviders(properties.providers());
        String configuredActiveProvider = properties.activeProvider();
        if (!checkedProviders.containsKey(configuredActiveProvider)) {
            throw new IllegalStateException(
                    "babiq.active-provider [" + configuredActiveProvider + "] 不在 providers 列表中,可用 id: "
                            + checkedProviders.keySet());
        }

        // Map.copyOf 不承诺保留插入顺序;这里显式包装 LinkedHashMap,确保 REST 列表顺序和 yml 一致。
        this.providersById = Collections.unmodifiableMap(new LinkedHashMap<>(checkedProviders));
        this.activeProviderId = new AtomicReference<>(configuredActiveProvider);
    }

    /**
     * 按 provider id 查询配置。
     *
     * @param providerId provider 唯一标识
     * @return 对应 provider 配置
     * @throws IllegalArgumentException providerId 不存在时抛出
     */
    public ModelProviderConfig get(String providerId) {
        ModelProviderConfig providerConfig = providersById.get(providerId);
        if (providerConfig == null) {
            throw new IllegalArgumentException(
                    "未知 provider id [" + providerId + "],可用 id: " + providersById.keySet());
        }
        return providerConfig;
    }

    /**
     * 返回当前激活 Provider 配置。
     *
     * @return 当前 active-provider 指向的配置
     */
    public ModelProviderConfig active() {
        return get(activeProviderId.get());
    }

    /**
     * 切换当前激活 Provider。
     *
     * @param providerId 目标 provider id
     * @throws IllegalArgumentException providerId 不存在时抛出
     */
    public void setActive(String providerId) {
        get(providerId);
        activeProviderId.set(providerId);
    }

    /**
     * 返回所有 Provider 配置。
     *
     * @return 按配置文件顺序排列的 provider 列表
     */
    public List<ModelProviderConfig> list() {
        return new ArrayList<>(providersById.values());
    }

    private static Map<String, ModelProviderConfig> indexProviders(List<ModelProviderConfig> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException("babiq.providers 不能为空,至少需要配置一个 provider");
        }

        Map<String, ModelProviderConfig> providerIndex = new LinkedHashMap<>();
        for (ModelProviderConfig providerConfig : providers) {
            // 保留配置文件顺序，桌面端下拉列表就能按用户在 yml 中写的顺序展示。
            ModelProviderConfig previousConfig = providerIndex.put(providerConfig.id(), providerConfig);
            if (previousConfig != null) {
                throw new IllegalStateException(
                        "babiq.providers 中存在重复 provider id [" + providerConfig.id() + "]");
            }
        }
        return providerIndex;
    }
}
