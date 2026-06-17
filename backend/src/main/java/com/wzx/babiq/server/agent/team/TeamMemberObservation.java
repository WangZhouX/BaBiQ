package com.wzx.babiq.server.agent.team;

/**
 * 团队成员单轮执行后的聚合观测。
 *
 * @param toolCallCount 成员在本 turn 中实际触发的工具调用次数
 * @param tokenEstimate 成员输出文本的 token 粗估值，不用于计费
 */
public record TeamMemberObservation(int toolCallCount, int tokenEstimate) {
}
