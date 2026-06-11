package com.wzx.babiq.server.model;

/**
 * 模型 Provider 类型。
 *
 * <p>该枚举决定 ChatClientFactory 应该把配置路由到哪个 ProviderFactory。
 * P1-2 只支持 DashScope 原生接入和 OpenAI 协议兼容接入两类,覆盖通义、
 * DeepSeek、OneAPI 中转和 Ollama。</p>
 */
public enum ProviderType {

    /** 阿里 DashScope 原生 starter,用于 qwen-plus / qwen-max 等模型。 */
    DASHSCOPE,

    /** OpenAI 协议兼容 endpoint,用于 DeepSeek / OneAPI / Ollama 等服务。 */
    OPENAI_COMPATIBLE,

    /** Anthropic Claude 官方 Messages API。 */
    ANTHROPIC
}
