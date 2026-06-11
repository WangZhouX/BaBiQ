package com.wzx.babiq.server.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param authMode 认证模式，未配置时默认为 API Key
 * @param model 模型名
 * @param apiKey API key,允许为空,调用该 provider 时再报明确错误
 * @param baseUrl OpenAI 兼容 endpoint 的 base-url
 * @param contextWindow 可选上下文窗口覆盖值
 */
public record ModelProviderConfig(
        @NotBlank String id,
        String name,
        @NotNull ProviderType type,
        ProviderAuthMode authMode,
        @NotBlank String model,
        String apiKey,
        String baseUrl,
        Integer contextWindow
) {

    @ConstructorBinding
    public ModelProviderConfig {
        if (authMode == null) {
            authMode = ProviderAuthMode.API_KEY;
        }
    }

    /**
     * 兼容旧配置和旧测试构造器，未显式传入 authMode 时使用 API Key。
     */
    public ModelProviderConfig(String id,
                               String name,
                               ProviderType type,
                               String model,
                               String apiKey,
                               String baseUrl,
                               Integer contextWindow) {
        this(id, name, type, ProviderAuthMode.API_KEY, model, apiKey, baseUrl, contextWindow);
    }

    /**
     * 返回补齐默认值后的认证模式。
     */
    public ProviderAuthMode effectiveAuthMode() {
        return authMode == null ? ProviderAuthMode.API_KEY : authMode;
    }

    /**
     * 返回用于 UI 或 REST 输出的展示名。
     *
     * @return name 非空时返回 name,否则返回 id
     */
    public String displayName() {
        return (name == null || name.isBlank()) ? id : name;
    }

    /**
     * 避免 record 默认 toString 泄露 API Key。
     *
     * <p>Provider 设置服务会把密钥放入 SecretStore，但运行期 ModelProviderConfig 仍需要携带明文
     * 给 Spring AI ProviderFactory 构建客户端。因此这里必须覆盖 toString，防止日志或测试输出误带密钥。</p>
     */
    @Override
    public String toString() {
        return "ModelProviderConfig[id=" + id
                + ", name=" + name
                + ", type=" + type
                + ", authMode=" + effectiveAuthMode().wireValue()
                + ", model=" + model
                + ", apiKey=<hidden>"
                + ", baseUrl=" + baseUrl
                + ", contextWindow=" + contextWindow
                + "]";
    }
}
