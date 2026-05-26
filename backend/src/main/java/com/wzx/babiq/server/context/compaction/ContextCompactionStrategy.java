package com.wzx.babiq.server.context.compaction;

/**
 * 短期上下文压缩策略。
 *
 * <p>该接口隔离“如何生成摘要”。默认实现使用 Spring AI ChatClient 结构化输出；
 * 后续也可以接入 Spring AI Alibaba 的 ContextEditingInterceptor 或 SummarizationHook 做候选实现。</p>
 */
@FunctionalInterface
public interface ContextCompactionStrategy {

    /**
     * 生成短期摘要。
     *
     * @param request 压缩请求
     * @return 压缩摘要结果
     */
    ContextCompactionStrategyResult summarize(ContextCompactionStrategyRequest request);
}
