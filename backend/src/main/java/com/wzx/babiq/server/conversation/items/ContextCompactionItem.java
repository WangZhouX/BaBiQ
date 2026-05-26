package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上下文压缩事件 item。
 *
 * <p>该 item 只表示“发生了一次压缩”，不承载完整摘要正文。摘要事实源保存在
 * bq_context_summaries 中，ContextAssembler 会排除这个 marker，避免模型把运行事件当作用户事实。</p>
 *
 * @param id item 标识
 * @param type 固定为 contextCompaction
 * @param compactionId 压缩审计记录 id，用于跳转或排查
 * @param status 压缩状态，例如 SUCCESS、SKIPPED、FAILED
 * @param summaryId 成功时安装的短期摘要 id
 * @param windowOrdinal 压缩成功后的窗口序号
 * @param estimatedTokensBefore 压缩前上下文预估 token
 * @param estimatedTokensAfter 摘要预估 token
 * @param message 面向 UI 的简短说明
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextCompactionItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        String compactionId,
        String status,
        String summaryId,
        Integer windowOrdinal,
        Integer estimatedTokensBefore,
        Integer estimatedTokensAfter,
        String message
) implements ThreadItem {

    /**
     * 创建只用于测试或旧协议兼容的上下文压缩 marker。
     *
     * @param id item 标识
     */
    public ContextCompactionItem(String id) {
        this(id, "contextCompaction", null, null, null, null, null, null, null);
    }
}
