package com.wzx.babiq.server.conversation.items;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 团队协作消息时间线协议 item。
 *
 * <p>该 item 用于右侧运行详情展示 supervisor 路由、成员摘要和用户直发 teammate 消息。
 * 它不是普通聊天消息，也不会注入下一轮模型上下文。</p>
 *
 * @param id 协议 item id
 * @param type 固定为 teamMessage
 * @param messageId 团队消息 id
 * @param teamId 所属团队 id
 * @param fromAgent 发送方：user、supervisor 或成员名
 * @param toAgent 接收方：supervisor、成员名或 all
 * @param messageType 消息类型：route、member_summary、direct_user、system
 * @param content 消息正文或短摘要
 * @param round 所属调度轮数
 * @param createdAt 创建时间，ISO-8601 文本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamMessageItem(
        String id,
        String type,
        String messageId,
        String teamId,
        String fromAgent,
        String toAgent,
        String messageType,
        String content,
        Integer round,
        String createdAt
) implements ThreadItem {
}
