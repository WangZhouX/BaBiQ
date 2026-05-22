package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DashScope 原生 ProviderFactory 的行为测试。
 *
 * <p>这些用例只验证本地对象构建和配置校验,不会向 DashScope 发起网络请求。</p>
 */
class DashScopeProviderFactoryTest {

    private final DashScopeProviderFactory factory = new DashScopeProviderFactory();

    @Test
    @DisplayName("supports 返回 DASHSCOPE")
    void supports_should_return_dashscope() {
        assertThat(factory.supports()).isEqualTo(ProviderType.DASHSCOPE);
    }

    @Test
    @DisplayName("正常配置构建 ChatModel")
    void build_should_create_chat_model_when_config_is_valid() {
        ModelProviderConfig config = new ModelProviderConfig(
                "dashscope-default",
                "通义千问",
                ProviderType.DASHSCOPE,
                "qwen-plus",
                "sk-fake-key",
                null,
                null
        );

        assertThat(factory.build(config)).isNotNull();
    }

    @Test
    @DisplayName("缺 api-key 时抛出带 provider id 的清晰错误")
    void build_should_reject_missing_api_key_with_provider_id() {
        ModelProviderConfig config = new ModelProviderConfig(
                "dashscope-default",
                null,
                ProviderType.DASHSCOPE,
                "qwen-plus",
                null,
                null,
                null
        );

        assertThatThrownBy(() -> factory.build(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key")
                .hasMessageContaining("dashscope-default");
    }

    @Test
    @DisplayName("空白 api-key 时同样抛出清晰错误")
    void build_should_reject_blank_api_key() {
        ModelProviderConfig config = new ModelProviderConfig(
                "dashscope-default",
                null,
                ProviderType.DASHSCOPE,
                "qwen-plus",
                "   ",
                null,
                null
        );

        assertThatThrownBy(() -> factory.build(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }
}
