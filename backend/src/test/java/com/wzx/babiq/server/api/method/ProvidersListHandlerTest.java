package com.wzx.babiq.server.api.method;

import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * model/providers/list 协议测试。
 *
 * <p>桌面端模型下拉框直接消费该响应,因此这里覆盖真实配置输出、
 * active 标记和 P1-4 暂不暴露本地 Llama3 三个关键契约。</p>
 */
class ProvidersListHandlerTest {

    @Test
    @DisplayName("返回真实 provider 配置并隐藏暂不支持的本地 Llama3")
    void handle_should_return_configured_providers_without_local_llama3() {
        ProvidersListHandler handler = new ProvidersListHandler(registry());

        Object payload = handler.handle(null, null);

        List<Map<String, Object>> providers = providersFrom(payload);
        assertThat(providers)
                .extracting(provider -> provider.get("id"))
                .containsExactly("dashscope-default", "deepseek-official", "oneapi-relay");
        assertThat(providers)
                .extracting(provider -> provider.get("id"))
                .doesNotContain("mock-provider", "ollama-local");
    }

    @Test
    @DisplayName("响应中标记当前 active provider 和 active model")
    void handle_should_mark_active_provider_and_model() {
        ProvidersListHandler handler = new ProvidersListHandler(registry());

        Object payload = handler.handle(null, null);

        Map<String, Object> activeProvider = providersFrom(payload).stream()
                .filter(provider -> "deepseek-official".equals(provider.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(activeProvider)
                .containsEntry("label", "DeepSeek 官方")
                .containsEntry("active", true);
        assertThat(modelsFrom(activeProvider))
                .singleElement()
                .satisfies(model -> assertThat(model)
                        .containsEntry("id", "deepseek-chat")
                        .containsEntry("label", "deepseek-chat")
                        .containsEntry("active", true));
    }

    private static ModelProviderRegistry registry() {
        return new ModelProviderRegistry(properties("deepseek-official",
                List.of(dashscope(), deepseek(), oneApi(), ollama())));
    }

    private static ModelProviderConfig dashscope() {
        return new ModelProviderConfig(
                "dashscope-default",
                "通义千问",
                ProviderType.DASHSCOPE,
                "qwen-plus",
                "sk-dashscope",
                null,
                null
        );
    }

    private static ModelProviderConfig deepseek() {
        return new ModelProviderConfig(
                "deepseek-official",
                "DeepSeek 官方",
                ProviderType.OPENAI_COMPATIBLE,
                "deepseek-chat",
                "sk-deepseek",
                "https://api.deepseek.com",
                null
        );
    }

    private static ModelProviderConfig oneApi() {
        return new ModelProviderConfig(
                "oneapi-relay",
                "我的中转",
                ProviderType.OPENAI_COMPATIBLE,
                "gpt-4o",
                "sk-oneapi",
                "https://relay.example.com/v1",
                null
        );
    }

    private static ModelProviderConfig ollama() {
        return new ModelProviderConfig(
                "ollama-local",
                "本地 Llama3",
                ProviderType.OPENAI_COMPATIBLE,
                "llama3:8b",
                "ollama",
                "http://localhost:11434/v1",
                null
        );
    }

    private static BaBiQProperties properties(String activeProvider, List<ModelProviderConfig> providers) {
        BaBiQProperties.ShortTerm shortTerm = new BaBiQProperties.ShortTerm(20);
        BaBiQProperties.Memory memory = new BaBiQProperties.Memory(shortTerm);
        return new BaBiQProperties(activeProvider, providers, memory);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providersFrom(Object payload) {
        Map<String, Object> response = (Map<String, Object>) payload;
        return (List<Map<String, Object>>) response.get("providers");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modelsFrom(Map<String, Object> provider) {
        return (List<Map<String, Object>>) provider.get("models");
    }
}
