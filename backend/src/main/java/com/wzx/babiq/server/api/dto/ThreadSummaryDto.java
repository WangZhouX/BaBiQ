package com.wzx.babiq.server.api.dto;

/**
 * 最近会话列表中的单条会话摘要。
 *
 * <p>这是 JSON-RPC 返回给桌面端的 DTO，不直接暴露数据库 Entity。字段命名跟协议保持一致，
 * 让 Kotlin 端可以用 kotlinx.serialization 直接解码。</p>
 *
 * @param threadId 协议层会话 id，桌面端点击最近列表后用它调用 thread/load
 * @param title 会话标题，当前由后端根据工作目录生成，后续 P2 设置系统可扩展为用户可编辑
 * @param cwd 会话绑定的工作目录，用来区分不同项目的最近对话
 * @param providerId 会话默认 Provider 快照，历史列表可用它提示本会话使用过的模型来源
 * @param model 会话默认模型快照，展示给用户看的模型名
 * @param status 会话状态，active 表示默认可见，archived 表示已软归档
 * @param lastTurnStatus 最近一轮 turn 状态；没有 turn 时为空
 * @param updatedAt 最近更新时间，使用 ISO 字符串避免跨平台时区解析歧义
 * @param messageCount 当前会话已保存的 item 数量，供 UI 显示简短统计
 */
public record ThreadSummaryDto(
        String threadId,
        String title,
        String cwd,
        String providerId,
        String model,
        String status,
        String lastTurnStatus,
        String updatedAt,
        long messageCount
) {
}
