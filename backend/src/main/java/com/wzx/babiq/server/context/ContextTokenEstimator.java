package com.wzx.babiq.server.context;

/**
 * 上下文 token 预估器。
 *
 * <p>P3-1 先用轻量接口隔离“字符到 token”的估算逻辑，后续 P3-2 可以把真实模型窗口、
 * tokenizer 或 Spring AI usage 回填接进来，而不需要改动 ContextAssembler 的领域逻辑。</p>
 */
public interface ContextTokenEstimator {

    /**
     * 预估一段文本进入模型上下文后大约占用多少 token。
     *
     * @param text 待估算文本；为空时代表没有可见内容
     * @return 非负 token 估算值，用于快照说明和后续预算裁剪
     */
    int estimate(String text);
}
