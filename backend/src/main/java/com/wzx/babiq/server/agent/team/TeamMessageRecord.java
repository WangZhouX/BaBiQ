package com.wzx.babiq.server.agent.team;

/**
 * 团队消息时间线记录。
 *
 * @param teamId 所属团队 id
 * @param messageId 协议层消息 id
 * @param threadId 所属 thread id；手动消息可为空
 * @param turnId 所属 turn id；手动消息可为空
 * @param fromAgent 发送方：user、supervisor 或成员名
 * @param toAgent 接收方：supervisor、成员名或 all
 * @param messageType 消息类型：route、member_summary、direct_user、system
 * @param content 消息正文或短摘要
 * @param routeDecisionJson supervisor 路由决策 JSON；非路由消息为空
 * @param round 所属调度轮数
 */
public record TeamMessageRecord(
        String teamId,
        String messageId,
        String threadId,
        String turnId,
        String fromAgent,
        String toAgent,
        String messageType,
        String content,
        String routeDecisionJson,
        int round
) {
}
