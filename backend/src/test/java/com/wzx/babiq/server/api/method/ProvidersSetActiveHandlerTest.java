package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.model.BaBiQProperties;
import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ModelProviderRegistry;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * model/providers/set-active 协议测试。
 *
 * <p>该 handler 是桌面端模型切换的写入口,测试需要证明它真的修改
 * {@link ModelProviderRegistry},而不是只返回 ok=true 的占位响应。</p>
 */
class ProvidersSetActiveHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("切换已配置 provider 并返回当前模型信息")
    void handle_should_switch_active_provider() {
        ModelProviderRegistry registry = registry();
        ProvidersSetActiveHandler handler = new ProvidersSetActiveHandler(registry);

        Object payload = handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "deepseek-official",
                "modelId", "deepseek-chat"
        )), null);

        Map<String, Object> response = responseFrom(payload);
        assertThat(response)
                .containsEntry("ok", true)
                .containsEntry("providerId", "deepseek-official")
                .containsEntry("modelId", "deepseek-chat");
        assertThat(registry.active().id()).isEqualTo("deepseek-official");
    }

    @Test
    @DisplayName("暂不支持切换到本地 Llama3")
    void handle_should_reject_local_llama3_provider() {
        ModelProviderRegistry registry = registry();
        ProvidersSetActiveHandler handler = new ProvidersSetActiveHandler(registry);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "ollama-local"
        )), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(exception).hasMessageContaining("本地 Llama3");
                });
        assertThat(registry.active().id()).isEqualTo("dashscope-default");
    }

    @Test
    @DisplayName("未知 provider id 返回 INVALID_PARAMS")
    void handle_should_reject_unknown_provider_id() {
        ProvidersSetActiveHandler handler = new ProvidersSetActiveHandler(registry());

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of(
                "providerId", "ghost"
        )), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(exception).hasMessageContaining("ghost");
                });
    }

    @Test
    @DisplayName("缺少 providerId 返回 INVALID_PARAMS")
    void handle_should_reject_missing_provider_id() {
        ProvidersSetActiveHandler handler = new ProvidersSetActiveHandler(registry());

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    private static ModelProviderRegistry registry() {
        return new ModelProviderRegistry(properties("dashscope-default",
                List.of(dashscope(), deepseek(), ollama())));
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
    private static Map<String, Object> responseFrom(Object payload) {
        return (Map<String, Object>) payload;
    }
}
