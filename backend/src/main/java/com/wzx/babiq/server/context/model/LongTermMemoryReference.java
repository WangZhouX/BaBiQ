package com.wzx.babiq.server.context.model;

/**
 * 长期记忆引用。
 *
 * <p>长期记忆在 prompt 中永远是 reference 层，必须保留来源和置信度，便于用户关闭、删除或审计。</p>
 *
 * @param artifactId 长期记忆产物 id
 * @param confidence 置信度标签，例如 high、medium、low
 * @param text 注入模型的短文本，不应是完整长文档
 */
public record LongTermMemoryReference(
        String artifactId,
        String confidence,
        String text
) {
}
