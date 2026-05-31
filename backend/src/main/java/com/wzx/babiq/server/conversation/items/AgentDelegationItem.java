package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 子 Agent 委派过程的协议 item。
 *
 * <p>它是 P6-1 的用户可见载体：父聊天流只看到一块“委派 explorer”的摘要，
 * 子 Agent 内部读文件、列目录、grep 等中间工具调用会聚合到这里，而不会变成普通工具消息。</p>
 *
 * @param id 协议 item id，同一轮委派的 added/updated 使用同一个 id
 * @param type 固定为 agentDelegation，供桌面端多态反序列化
 * @param delegationId 一次委派的稳定 id，用于 UI、运行记录和工具调用归属串联
 * @param parentAgent 发起委派的父 Agent 名称，P6-1 为 babiq_agent
 * @param childAgent 被委派的子 Agent 名称，P6-1 为 explorer
 * @param status running、completed 或 failed
 * @param mode 委派模式，P6-1 为 READ_ONLY_TOOL
 * @param summary 子 Agent 当前摘要；running 时可为空或显示正在调用的只读工具
 * @param toolCallCount 子 Agent 已聚合的工具调用次数
 * @param tokenEstimate 子 Agent 期间增加的 turn token 粗估值，不承诺 per-agent 精确计费
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDelegationItem(
        String id,
        String type,
        String delegationId,
        String parentAgent,
        String childAgent,
        String status,
        String mode,
        String summary,
        Integer toolCallCount,
        Integer tokenEstimate
) implements ThreadItem {
}
