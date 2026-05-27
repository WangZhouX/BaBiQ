package com.wzx.babiq.server.memory.retrieval;

import com.wzx.babiq.server.context.model.LongTermMemoryReference;

import java.util.List;

/**
 * 长期记忆检索结果。
 *
 * @param references 可注入 reference 层的记忆片段
 * @param tokenEstimate 片段 token 估算总数
 */
public record LongTermMemoryRetrievalResult(
        List<LongTermMemoryReference> references,
        int tokenEstimate
) {

    /**
     * 空结果。
     */
    public static LongTermMemoryRetrievalResult empty() {
        return new LongTermMemoryRetrievalResult(List.of(), 0);
    }

    /**
     * 防御性复制引用列表。
     */
    public LongTermMemoryRetrievalResult {
        references = references == null ? List.of() : List.copyOf(references);
    }
}
