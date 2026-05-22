package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Codex 协作工具调用占位 item。
 *
 * <p>该类型为后续接入文件编辑、浏览器、图片等 Codex 风格协作工具预留协议
 * 标签。P1-1 只保留 id 和 type。</p>
 *
 * @param id item 标识
 * @param type 固定为 collabToolCall
 */
public record CollabToolCallItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type
) implements ThreadItem {

    /**
     * 创建协作工具调用占位 item。
     *
     * @param id item 标识
     */
    public CollabToolCallItem(String id) {
        this(id, "collabToolCall");
    }
}
