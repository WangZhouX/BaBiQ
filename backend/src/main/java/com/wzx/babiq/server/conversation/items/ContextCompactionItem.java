package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上下文压缩占位 item。
 *
 * <p>当长对话需要压缩上下文时,后续阶段会用该类型提示桌面端发生了记忆压缩。
 * P1-1 只声明 type,不实现压缩算法。</p>
 *
 * @param id item 标识
 * @param type 固定为 contextCompaction
 */
public record ContextCompactionItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type
) implements ThreadItem {

    /**
     * 创建上下文压缩占位 item。
     *
     * @param id item 标识
     */
    public ContextCompactionItem(String id) {
        this(id, "contextCompaction");
    }
}
