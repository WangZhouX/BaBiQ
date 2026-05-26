package com.wzx.babiq.server.memory.extract;

/**
 * 长期记忆 Phase1 抽取器端口。
 *
 * <p>Phase1 的职责是从一个已经 idle 的会话窗口里提炼“未来仍有价值的事实”。接口本身不绑定 Spring AI，
 * 这样生产环境可以使用 Spring AI structured output，测试也可以注入确定性的 fake extractor。</p>
 */
@FunctionalInterface
public interface MemoryStageOneExtractor {

    /**
     * 从会话片段中抽取长期记忆候选。
     *
     * @param request 包含 thread 快照、item 输入和 token 预算的抽取请求
     * @return 抽取结果；没有可沉淀内容时返回 {@link MemoryStageOneResult#empty()}
     */
    MemoryStageOneResult extract(MemoryStageOneRequest request);
}
