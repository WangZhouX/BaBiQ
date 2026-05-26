package com.wzx.babiq.server.context.compaction;

/**
 * 压缩策略返回的摘要结果。
 *
 * @param summary 模型生成的短期摘要正文
 */
public record ContextCompactionStrategyResult(String summary) {
}
