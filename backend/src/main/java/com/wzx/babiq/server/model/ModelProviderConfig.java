package com.wzx.babiq.server.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个模型 Provider 配置。
 *
 * <p>该 record 对应 application.yml 中的 babiq.providers[*]。它只承载配置,
 * 不主动构建 ChatModel;真正的参数完整性校验由对应 ProviderFactory 在调用时
 * 完成,这样应用可以在缺少某个 provider api-key 时仍正常启动。</p>
 *
 * @param id provider 唯一标识
 * @param name UI 展示名,为空时使用 id
 * @param type provider 类型
 * @param model 模型名
 * @param apiKey API key,允许为空,调用该 provider 时再报明确错误
 * @param baseUrl OpenAI 兼容 endpoint 的 base-url
 * @param contextWindow 可选上下文窗口覆盖值
 */
public record ModelProviderConfig(
        @NotBlank String id,
        String name,
        @NotNull ProviderType type,
        @NotBlank String model,
        String apiKey,
        String baseUrl,
        Integer contextWindow
) {

    /**
     * 返回用于 UI 或 REST 输出的展示名。
     *
     * @return name 非空时返回 name,否则返回 id
     */
    public String displayName() {
        return (name == null || name.isBlank()) ? id : name;
    }
}
