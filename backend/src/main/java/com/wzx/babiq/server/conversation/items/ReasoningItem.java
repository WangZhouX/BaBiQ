package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 推理摘要 item。
 *
 * <p>P1-1 只定义 schema,不输出推理内容。后续如果模型或 agent loop 产生可展示
 * 的思考摘要,桌面端会通过该类型渲染。</p>
 *
 * @param id item 标识
 * @param type 固定为 reasoning
 * @param text 可展示的推理摘要
 */
public record ReasoningItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) String text
) implements ThreadItem {
}
