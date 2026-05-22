package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Review 模式占位 item。
 *
 * <p>Review 模式用于后续表达代码审查型 agent 输出。P1-1 不实现审查流程,只
 * 让协议枚举闭合。</p>
 *
 * @param id item 标识
 * @param type 固定为 reviewMode
 */
public record ReviewModeItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type
) implements ThreadItem {

    /**
     * 创建 Review 模式占位 item。
     *
     * @param id item 标识
     */
    public ReviewModeItem(String id) {
        this(id, "reviewMode");
    }
}
