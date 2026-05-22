package com.wzx.babiq.server.model.provider;

import com.wzx.babiq.server.model.ModelProviderConfig;
import com.wzx.babiq.server.model.ProviderType;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 单类 Provider 的 ChatModel 构建工厂。
 *
 * <p>ProviderFactory 只负责把单个 {@link ModelProviderConfig} 转换为底层
 * {@link ChatModel};短期记忆、ChatClient 缓存和 conversationId 隔离由上层
 * ChatClientFactory 统一处理。</p>
 */
public interface ProviderFactory {

    /**
     * 返回该工厂支持的 Provider 类型。
     *
     * @return 当前工厂可以构建的 ProviderType
     */
    ProviderType supports();

    /**
     * 根据 provider 配置构建 ChatModel。
     *
     * @param config 单个 provider 的配置
     * @return 已绑定模型名和 endpoint 的 ChatModel
     * @throws IllegalStateException 当配置缺少当前 provider 必需字段时抛出
     */
    ChatModel build(ModelProviderConfig config);
}
