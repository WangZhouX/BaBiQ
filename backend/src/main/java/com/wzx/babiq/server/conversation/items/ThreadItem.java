package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 对话线程中的协议 item 基类。
 *
 * <p>ThreadItem 是桌面端渲染 Agent 执行过程的最小单位。P1-1 先声明 12 种
 * 协议类型,其中 UserMessageItem 和 AgentMessageItem 会真实出现在 mock turn
 * 流中,其余类型先作为稳定 schema 占位,等 P1-3 接工具和审批后再扩展字段。</p>
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessageItem.class, name = "userMessage"),
        @JsonSubTypes.Type(value = AgentMessageItem.class, name = "agentMessage"),
        @JsonSubTypes.Type(value = ReasoningItem.class, name = "reasoning"),
        @JsonSubTypes.Type(value = PlanItem.class, name = "plan"),
        @JsonSubTypes.Type(value = CommandExecutionItem.class, name = "commandExecution"),
        @JsonSubTypes.Type(value = FileChangeItem.class, name = "fileChange"),
        @JsonSubTypes.Type(value = McpToolCallItem.class, name = "mcpToolCall"),
        @JsonSubTypes.Type(value = CollabToolCallItem.class, name = "collabToolCall"),
        @JsonSubTypes.Type(value = WebSearchItem.class, name = "webSearch"),
        @JsonSubTypes.Type(value = ImageViewItem.class, name = "imageView"),
        @JsonSubTypes.Type(value = ReviewModeItem.class, name = "reviewMode"),
        @JsonSubTypes.Type(value = ContextCompactionItem.class, name = "contextCompaction"),
        @JsonSubTypes.Type(value = AgentDelegationItem.class, name = "agentDelegation"),
        @JsonSubTypes.Type(value = OrchestrationItem.class, name = "orchestration"),
        @JsonSubTypes.Type(value = TeamItem.class, name = "team"),
        @JsonSubTypes.Type(value = TeamMessageItem.class, name = "teamMessage"),
        @JsonSubTypes.Type(value = WorkUnitItem.class, name = "workUnit"),
        @JsonSubTypes.Type(value = ApplicationActionItem.class, name = "applicationAction"),
        @JsonSubTypes.Type(value = TurnSummaryItem.class, name = "turnSummary")
})
public sealed interface ThreadItem permits
        UserMessageItem,
        AgentMessageItem,
        ReasoningItem,
        PlanItem,
        CommandExecutionItem,
        FileChangeItem,
        McpToolCallItem,
        CollabToolCallItem,
        WebSearchItem,
        ImageViewItem,
        ReviewModeItem,
        ContextCompactionItem,
        AgentDelegationItem,
        OrchestrationItem,
        TeamItem,
        TeamMessageItem,
        WorkUnitItem,
        ApplicationActionItem,
        TurnSummaryItem {

    /**
     * 返回 item 标识。
     *
     * @return 协议层 item id,固定以 it_ 开头
     */
    String id();

    /**
     * 返回 item 类型标签。
     *
     * @return Jackson 多态反序列化使用的 type 值
     */
    String type();
}
