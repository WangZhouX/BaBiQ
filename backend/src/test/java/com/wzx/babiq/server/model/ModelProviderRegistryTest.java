package com.wzx.babiq.server.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Provider 注册中心测试。
 *
 * <p>注册中心是配置进入运行期的第一道边界,因此重点覆盖重复 id、
 * active-provider 不存在和未知 id 查询三类启动期/运行期错误。</p>
 */
class ModelProviderRegistryTest {

    @Test
    @DisplayName("get 按 id 返回对应 provider 配置")
    void get_should_return_provider_config_by_id() {
        ModelProviderRegistry registry = new ModelProviderRegistry(
                properties("dashscope-default", List.of(dashscope(), deepseek()))
        );

        assertThat(registry.get("dashscope-default").model()).isEqualTo("qwen-plus");
        assertThat(registry.get("deepseek-official").type()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("get 未知 id 抛出包含可用 id 的清晰错误")
    void get_should_reject_unknown_id_with_available_ids() {
        ModelProviderRegistry registry = new ModelProviderRegistry(
                properties("dashscope-default", List.of(dashscope()))
        );

        assertThatThrownBy(() -> registry.get("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("dashscope-default");
    }

    @Test
    @DisplayName("active 返回当前激活 provider 配置")
    void active_should_return_active_provider_config() {
        ModelProviderRegistry registry = new ModelProviderRegistry(
                properties("deepseek-official", List.of(dashscope(), deepseek()))
        );

        assertThat(registry.active().id()).isEqualTo("deepseek-official");
    }

    @Test
    @DisplayName("setActive 切换当前激活 provider")
    void set_active_should_switch_active_provider() {
        ModelProviderRegistry registry = new ModelProviderRegistry(
                properties("dashscope-default", List.of(dashscope(), deepseek()))
        );

        registry.setActive("deepseek-official");

        assertThat(registry.active().id()).isEqualTo("deepseek-official");
    }

    @Test
    @DisplayName("setActive 未知 id 时拒绝切换")
    void set_active_should_reject_unknown_id() {
        ModelProviderRegistry registry = new ModelProviderRegistry(
                properties("dashscope-default", List.of(dashscope()))
        );

        assertThatThrownBy(() -> registry.setActive("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("list 返回所有 provider 且保持 yml 顺序")
    void list_should_return_all_providers_in_config_order() {
        ModelProviderRegistry registry = new ModelProviderRegistry(
                properties("dashscope-default", List.of(dashscope(), deepseek()))
        );

        assertThat(registry.list())
                .extracting(ModelProviderConfig::id)
                .containsExactly("dashscope-default", "deepseek-official");
    }

    @Test
    @DisplayName("重复 provider id 在启动期被拒绝")
    void constructor_should_reject_duplicate_provider_id() {
        ModelProviderConfig duplicate = new ModelProviderConfig(
                "dashscope-default",
                "重复通义",
                ProviderType.DASHSCOPE,
                "qwen-turbo",
                "sk-duplicate",
                null,
                null
        );

        assertThatThrownBy(() -> new ModelProviderRegistry(
                properties("dashscope-default", List.of(dashscope(), duplicate))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复")
                .hasMessageContaining("dashscope-default");
    }

    @Test
    @DisplayName("active-provider 不在 providers 列表中时启动期拒绝")
    void constructor_should_reject_active_provider_not_in_list() {
        assertThatThrownBy(() -> new ModelProviderRegistry(
                properties("ghost", List.of(dashscope()))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-provider")
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("providers 为空时启动期拒绝")
    void constructor_should_reject_empty_providers() {
        assertThatThrownBy(() -> new ModelProviderRegistry(
                properties("dashscope-default", List.of())
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("providers");
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

    private static BaBiQProperties properties(String activeProvider, List<ModelProviderConfig> providers) {
        BaBiQProperties.ShortTerm shortTerm = new BaBiQProperties.ShortTerm(20);
        BaBiQProperties.Memory memory = new BaBiQProperties.Memory(shortTerm);
        return new BaBiQProperties(activeProvider, providers, memory);
    }
}
