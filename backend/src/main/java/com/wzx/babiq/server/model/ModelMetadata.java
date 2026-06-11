package com.wzx.babiq.server.model;

import java.util.Map;

import static java.util.Map.entry;

/**
 * 模型元数据表。
 *
 * <p>Spring AI 和 Spring AI Alibaba 不提供统一的“模型上下文窗口查询”API。
 * BaBiQ 按 D20 在本类集中维护主流模型的 context window 映射,供 provider
 * 列表展示、后续摘要阈值计算和上下文预算判断复用。</p>
 */
public final class ModelMetadata {

    /** 未知模型的默认上下文窗口,使用保守 32K 避免高估上下文预算。 */
    public static final int DEFAULT_CONTEXT_WINDOW = 32_768;

    private static final Map<String, Integer> CONTEXT_WINDOWS = Map.ofEntries(
            // 阿里通义系列。
            entry("qwen-plus", 1_000_000),
            entry("qwen-turbo", 128_000),
            entry("qwen-max", 262_144),
            entry("qwen3-max", 262_144),
            entry("qwq-plus", 131_072),

            // DeepSeek 系列。
            entry("deepseek-chat", 128_000),
            entry("deepseek-reasoner", 128_000),
            entry("deepseek-v4", 1_000_000),

            // OpenAI 系列。
            entry("gpt-4o", 128_000),
            entry("gpt-4o-mini", 128_000),
            entry("gpt-4.1", 1_000_000),
            entry("gpt-5", 1_000_000),
            entry("o1", 200_000),
            entry("o1-mini", 128_000),

            // Anthropic 系列。
            entry("claude-opus-4-8", 1_000_000),
            entry("claude-opus-4-7", 200_000),
            entry("claude-sonnet-4-6", 1_000_000),
            entry("claude-haiku-4-5", 200_000),

            // Ollama / 本地模型。
            entry("llama3:8b", 8_192),
            entry("qwen2.5-coder:7b", 32_768),
            entry("qwen2.5-coder:32b", 32_768)
    );

    private ModelMetadata() {
    }

    /**
     * 查询模型上下文窗口。
     *
     * @param model 模型名,大小写不敏感
     * @return 已知模型返回内置窗口,未知模型返回 {@link #DEFAULT_CONTEXT_WINDOW}
     * @throws IllegalArgumentException model 为 null 或空白时抛出
     */
    public static int contextWindowOf(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空,无法查询 context window");
        }
        return CONTEXT_WINDOWS.getOrDefault(model.toLowerCase(), DEFAULT_CONTEXT_WINDOW);
    }
}
