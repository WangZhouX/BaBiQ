package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Agent 文本消息 item。
 *
 * <p>P1-1 中该类型承载 mock agentMessage。它同时支持完整文本 text 和增量文本
 * textDelta,是为了让 P1-2/P1-3 接真实流式模型时不再修改基础协议。</p>
 *
 * @param id item 标识
 * @param type 固定为 agentMessage
 * @param text 完整 Agent 文本
 * @param textDelta 增量 Agent 文本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessageItem(
        @JsonProperty(required = true) String id,
        @JsonProperty(required = true) String type,
        String text,
        String textDelta
) implements ThreadItem {

    /**
     * 创建完整文本 Agent 消息。
     *
     * @param id item 标识
     * @param text 完整 Agent 文本
     * @return type 已固定为 agentMessage 的 item
     */
    public static AgentMessageItem full(String id, String text) {
        return new AgentMessageItem(id, "agentMessage", text, null);
    }

    /**
     * 创建增量文本 Agent 消息。
     *
     * @param id item 标识
     * @param textDelta 增量 Agent 文本
     * @return 只携带 textDelta 的 agentMessage item
     */
    public static AgentMessageItem delta(String id, String textDelta) {
        return new AgentMessageItem(id, "agentMessage", null, textDelta);
    }
}
