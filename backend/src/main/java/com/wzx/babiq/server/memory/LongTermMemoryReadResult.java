package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.context.model.LongTermMemoryReference;

import java.util.List;

/**
 * 长期记忆读取结果。
 *
 * @param references 本轮上下文窗口应注入的长期记忆 reference
 * @param tokenEstimate 注入文本 token 估算总数
 */
public record LongTermMemoryReadResult(
        List<LongTermMemoryReference> references,
        int tokenEstimate
) {

    /**
     * 空读取结果。
     */
    public static LongTermMemoryReadResult empty() {
        return new LongTermMemoryReadResult(List.of(), 0);
    }
}
