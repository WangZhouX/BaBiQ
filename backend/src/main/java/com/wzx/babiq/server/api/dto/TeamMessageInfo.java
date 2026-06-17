package com.wzx.babiq.server.api.dto;

/**
 * 团队时间线消息。
 *
 * @param teamId 所属团队 id
 * @param messageId 消息协议 id
 * @param threadId 所属会话 id
 * @param turnId 所属 turn id
 * @param fromAgent 发送方
 * @param toAgent 接收方
 * @param messageType 消息类型
 * @param content 消息内容
 * @param routeDecisionJson supervisor 路由决策 JSON
 * @param round 调度轮数
 */
public record TeamMessageInfo(
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
