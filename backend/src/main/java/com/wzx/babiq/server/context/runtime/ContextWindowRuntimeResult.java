package com.wzx.babiq.server.context.runtime;

import com.wzx.babiq.server.context.model.ContextAssemblyResult;

/**
 * ContextWindowRuntime 的单轮输出。
 *
 * @param snapshotId 本轮上下文快照 id；禁用运行时时可为空。
 * @param rawUserText 原始用户输入，继续作为聊天历史 item 保存。
 * @param modelInputText 临时模型输入文本，只传给 ReactAgent，不写回聊天历史。
 * @param assemblyResult ContextAssembler 输出，便于测试和后续扩展读取结构化结果。
 */
public record ContextWindowRuntimeResult(
        String snapshotId,
        String rawUserText,
        String modelInputText,
        ContextAssemblyResult assemblyResult
) {
    /**
     * 创建已准备好的运行时输出。
     *
     * @param snapshotId 快照 id
     * @param rawUserText 原始用户输入
     * @param modelInputText 临时模型输入
     * @return 运行时输出
     */
    public static ContextWindowRuntimeResult prepared(String snapshotId, String rawUserText, String modelInputText) {
        return new ContextWindowRuntimeResult(snapshotId, rawUserText, modelInputText, null);
    }

    /**
     * Runtime results contain the complete transient prompt and nested
     * content-bearing context objects. Never expand them into logs.
     */
    @Override
    public String toString() {
        return "ContextWindowRuntimeResult[snapshotId=%s, rawUserText=<redacted>, "
                .formatted(snapshotId)
                + "modelInputText=<redacted>, assemblyResult=<redacted>]";
    }
}
