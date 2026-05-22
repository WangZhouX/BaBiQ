package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Web 搜索占位 item。
 *
 * <p>Web 搜索属于 P1-3+ 的工具能力。P1-1 提前固定 type 标签,避免后续新增
 * 工具时改动基础多态注册。</p>
 *
 * @param id item 标识
 * @param type 固定为 webSearch
 */
public record WebSearchItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type
) implements ThreadItem {

    /**
     * 创建 Web 搜索占位 item。
     *
     * @param id item 标识
     */
    public WebSearchItem(String id) {
        this(id, "webSearch");
    }
}
