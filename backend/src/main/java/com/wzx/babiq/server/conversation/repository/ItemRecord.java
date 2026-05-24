package com.wzx.babiq.server.conversation.repository;

import java.time.Instant;

/**
 * Item 持久化边界使用的领域记录。
 *
 * <p>payloadJson 保存协议原始内容，调用方不需要知道数据库中是否拆了字段；后续协议扩展时也能保持
 * repository API 稳定。</p>
 *
 * @param itemId 协议层 itemId
 * @param threadId 所属 threadId
 * @param turnId 所属 turnId
 * @param type item 类型
 * @param sequenceNo 会话内显示顺序
 * @param payloadJson 原始 JSON payload
 * @param status item 状态
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record ItemRecord(
        String itemId,
        String threadId,
        String turnId,
        String type,
        int sequenceNo,
        String payloadJson,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 创建一个创建时间和更新时间相同的 item 记录。
     *
     * @return 可直接保存的 item record
     */
    public static ItemRecord of(
            String itemId,
            String threadId,
            String turnId,
            String type,
            int sequenceNo,
            String payloadJson,
            String status,
            Instant now) {
        return new ItemRecord(itemId, threadId, turnId, type, sequenceNo, payloadJson, status, now, now);
    }
}
