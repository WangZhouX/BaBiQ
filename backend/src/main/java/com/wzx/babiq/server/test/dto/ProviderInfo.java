package com.wzx.babiq.server.test.dto;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;

/**
 * P1-2 临时 provider 列表响应项。
 *
 * <p>该结构只暴露前端或人工烟测需要看的安全字段,不会把 api-key 写回响应。</p>
 *
 * @param id provider 唯一标识
 * @param name provider 展示名
 * @param type provider 类型
 * @param model 模型名
 * @param active 是否为当前激活 provider
 * @param contextWindow 模型上下文窗口
 */
public record ProviderInfo(
        String id,
        String name,
        ProviderType type,
        String model,
        boolean active,
        int contextWindow
) {

    /**
     * 从 provider 配置转换为响应项。
     *
     * @param config provider 配置
     * @param active 是否当前激活
     * @param contextWindow 上下文窗口
     * @return 不含敏感字段的 provider 响应项
     */
    public static ProviderInfo from(ModelProviderConfig config, boolean active, int contextWindow) {
        return new ProviderInfo(
                config.id(),
                config.displayName(),
                config.type(),
                config.model(),
                active,
                contextWindow
        );
    }
}
