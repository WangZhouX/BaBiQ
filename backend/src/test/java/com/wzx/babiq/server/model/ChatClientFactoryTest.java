package com.wzx.babiq.server.model;

import com.wzx.babiq.server.model.provider.ProviderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatClientFactory 的本地构建行为测试。
 *
 * <p>这里使用内存中的假 ChatModel,避免单元测试依赖外部模型服务或 api-key。
 * 跨轮记忆是否注入 prompt 会在 Task 9 用专门的集成测试覆盖。</p>
 */
class ChatClientFactoryTest {

    @Test
    @DisplayName("resolve 按 provider id 返回非空 ChatClient")
    void resolve_should_return_chat_client_by_provider_id() {
        ChatClientFactory factory = newFactory(properties(), List.of(fakeFactory(ProviderType.DASHSCOPE)));

        ChatClient chatClient = factory.resolve("dashscope-default");

        assertThat(chatClient).isNotNull();
    }

    @Test
    @DisplayName("resolve 同一 provider id 多次返回同一 ChatClient 实例")
    void resolve_should_cache_chat_client_per_provider_id() {
        ChatClientFactory factory = newFactory(properties(), List.of(fakeFactory(ProviderType.DASHSCOPE)));

        ChatClient firstChatClient = factory.resolve("dashscope-default");
        ChatClient secondChatClient = factory.resolve("dashscope-default");

        assertThat(firstChatClient).isSameAs(secondChatClient);
    }

    @Test
    @DisplayName("resolve 不同 provider id 返回不同 ChatClient 实例")
    void resolve_should_create_distinct_clients_for_distinct_provider_ids() {
        BaBiQProperties properties = properties(dashscope(), deepseek(), "dashscope-default");
        ChatClientFactory factory = newFactory(properties, List.of(
                fakeFactory(ProviderType.DASHSCOPE),
                fakeFactory(ProviderType.OPENAI_COMPATIBLE)
        ));

        ChatClient dashScopeClient = factory.resolve("dashscope-default");
        ChatClient deepSeekClient = factory.resolve("deepseek-official");

        assertThat(dashScopeClient).isNotSameAs(deepSeekClient);
    }

    @Test
    @DisplayName("active 返回当前激活 provider 的 ChatClient")
    void active_should_return_active_provider_client() {
        ChatClientFactory factory = newFactory(properties(), List.of(fakeFactory(ProviderType.DASHSCOPE)));

        ChatClient activeClient = factory.active();

        assertThat(activeClient).isSameAs(factory.resolve("dashscope-default"));
    }

    @Test
    @DisplayName("resolveChatModel 返回原始 ChatModel，不包 memory advisor")
    void resolveChatModel_should_return_raw_chat_model() {
        ChatClientFactory factory = newFactory(properties(), List.of(fakeFactory(ProviderType.DASHSCOPE)));

        ChatModel chatModel = factory.resolveChatModel("dashscope-default");

        assertThat(chatModel).isNotNull();
        assertThat(((EchoChatModel) chatModel).providerId()).isEqualTo("dashscope-default");
    }

    @Test
    @DisplayName("resolve 未知 provider id 抛出清晰错误")
    void resolve_should_reject_unknown_provider_id() {
        ChatClientFactory factory = newFactory(properties(), List.of(fakeFactory(ProviderType.DASHSCOPE)));

        assertThatThrownBy(() -> factory.resolve("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("缺少对应 ProviderFactory 时抛出清晰错误")
    void resolve_should_reject_missing_provider_factory() {
        BaBiQProperties properties = properties(dashscope(), deepseek(), "dashscope-default");
        ChatClientFactory factory = newFactory(properties, List.of(fakeFactory(ProviderType.DASHSCOPE)));

        assertThatThrownBy(() -> factory.resolve("deepseek-official"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_COMPATIBLE");
    }

    @Test
    @DisplayName("重复 ProviderFactory 类型在构造期拒绝")
    void constructor_should_reject_duplicate_provider_factory_type() {
        BaBiQProperties properties = properties();
        List<ProviderFactory> factories = List.of(
                fakeFactory(ProviderType.DASHSCOPE),
                fakeFactory(ProviderType.DASHSCOPE)
        );

        assertThatThrownBy(() -> newFactory(properties, factories))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DASHSCOPE");
    }

    private static ChatClientFactory newFactory(BaBiQProperties properties, List<ProviderFactory> factories) {
        return new ChatClientFactory(new ModelProviderRegistry(properties), factories, properties);
    }

    private static ProviderFactory fakeFactory(ProviderType providerType) {
        return new ProviderFactory() {
            @Override
            public ProviderType supports() {
                return providerType;
            }

            @Override
            public ChatModel build(ModelProviderConfig config) {
                return new EchoChatModel(config.id());
            }
        };
    }

    private static BaBiQProperties properties() {
        return properties(dashscope(), null, "dashscope-default");
    }

    private static BaBiQProperties properties(ModelProviderConfig firstProvider,
                                              ModelProviderConfig secondProvider,
                                              String activeProvider) {
        List<ModelProviderConfig> providers = secondProvider == null
                ? List.of(firstProvider)
                : List.of(firstProvider, secondProvider);
        BaBiQProperties.ShortTerm shortTerm = new BaBiQProperties.ShortTerm(20);
        BaBiQProperties.Memory memory = new BaBiQProperties.Memory(shortTerm);
        return new BaBiQProperties(activeProvider, providers, memory);
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

    /**
     * 测试用 ChatModel,只返回固定文本,避免触碰任何外部网络。
     */
    private record EchoChatModel(String providerId) implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            AssistantMessage assistantMessage = new AssistantMessage("echo from " + providerId);
            return new ChatResponse(List.of(new Generation(assistantMessage)));
        }
    }
}
