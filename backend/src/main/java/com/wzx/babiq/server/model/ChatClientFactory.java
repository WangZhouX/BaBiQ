package com.wzx.babiq.server.model;

import com.wzx.babiq.server.model.provider.ProviderFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 统一工厂。
 *
 * <p>这是 P1-2 的 Provider 层入口。它按 providerId 从注册中心读取配置,
 * 再交给对应 {@link ProviderFactory} 构建 {@link ChatModel},最后包装为已经挂好
 * {@link MessageChatMemoryAdvisor} 的 {@link ChatClient}。</p>
 *
 * <p>D18 要求短期记忆按 providerId 复用:同一个 providerId 多次 resolve 返回同一
 * ChatClient 实例,它内部共享同一个 {@link ChatMemory};实际会话隔离由调用方通过
 * {@link ChatMemory#CONVERSATION_ID} 参数传入 threadId。</p>
 */
@Component
public class ChatClientFactory {

    private final ModelProviderRegistry registry;
    private final Map<ProviderType, ProviderFactory> factoriesByType;
    private final int maxMessages;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 创建 ChatClientFactory。
     *
     * @param registry provider 配置注册中心
     * @param factories Spring 注入的各类 ProviderFactory
     * @param properties BaBiQ 根配置,用于读取短期记忆窗口大小
     * @throws IllegalStateException ProviderFactory 类型重复时抛出
     */
    public ChatClientFactory(ModelProviderRegistry registry,
                             List<ProviderFactory> factories,
                             BaBiQProperties properties) {
        this.registry = registry;
        this.factoriesByType = indexFactories(factories);
        this.maxMessages = properties.memory().shortTerm().maxMessages();
    }

    /**
     * 按 providerId 返回已挂短期记忆 advisor 的 ChatClient。
     *
     * @param providerId provider 唯一标识
     * @return 缓存后的 ChatClient 实例
     * @throws IllegalArgumentException providerId 不存在时抛出
     * @throws IllegalStateException provider 类型没有对应工厂时抛出
     */
    public ChatClient resolve(String providerId) {
        return clientCache.computeIfAbsent(providerId, this::buildClient);
    }

    /**
     * 按 providerId 解析原始 ChatModel，不附加 memory advisor。
     *
     * <p>ReactAgent 自己会接管 ReAct 循环和记忆检查点，P1-3a 需要的是未包装的模型对象。</p>
     *
     * @param providerId provider 唯一标识；传入 null 时使用当前 active provider
     * @return 原始 ChatModel
     */
    public ChatModel resolveChatModel(String providerId) {
        String effectiveProviderId = providerId == null ? registry.active().id() : providerId;
        ModelProviderConfig providerConfig = registry.get(effectiveProviderId);
        ProviderFactory providerFactory = factoriesByType.get(providerConfig.type());
        if (providerFactory == null) {
            throw new IllegalStateException("没有注册 ProviderFactory,type=" + providerConfig.type()
                    + ",providerId=" + effectiveProviderId);
        }
        return providerFactory.build(providerConfig);
    }

    /**
     * 返回当前激活 provider 的 ChatClient。
     *
     * @return active-provider 对应的 ChatClient
     */
    public ChatClient active() {
        return resolve(registry.active().id());
    }

    private ChatClient buildClient(String providerId) {
        ChatModel chatModel = resolveChatModel(providerId);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages)
                .build();
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .build();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

    private static Map<ProviderType, ProviderFactory> indexFactories(List<ProviderFactory> factories) {
        Map<ProviderType, ProviderFactory> providerFactoryIndex = new EnumMap<>(ProviderType.class);
        for (ProviderFactory factory : factories) {
            ProviderFactory previousFactory = providerFactoryIndex.put(factory.supports(), factory);
            if (previousFactory != null) {
                throw new IllegalStateException("重复 ProviderFactory,type=" + factory.supports());
            }
        }
        return providerFactoryIndex;
    }
}
