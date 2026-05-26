package com.wzx.babiq.server.context;

/**
 * 朴素上下文 token 预估器。
 *
 * <p>真实 tokenizer 往往和模型/provider 绑定，P3-1 不在底座阶段引入 provider-specific 依赖。
 * 这里采用保守的字符近似值，让 snapshot 先具备可解释的 token 预算字段。</p>
 */
public class ApproximateContextTokenEstimator implements ContextTokenEstimator {

    /** 中文和英文混合场景下的经验比例，取偏保守值避免低估上下文压力。 */
    private static final int CHARS_PER_TOKEN = 3;

    /**
     * 使用字符长度近似 token 数，最少返回 1 以便非空短文本在快照中可见。
     *
     * @param text 待估算文本
     * @return token 估算值
     */
    @Override
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN));
    }
}
