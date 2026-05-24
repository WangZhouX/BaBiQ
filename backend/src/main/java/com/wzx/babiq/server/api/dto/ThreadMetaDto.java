package com.wzx.babiq.server.api.dto;

/**
 * thread/load 响应中的会话元信息。
 *
 * @param threadId 协议层会话 id
 * @param title 会话标题
 * @param cwd 会话绑定的工作目录
 * @param status 会话状态，active 或 archived
 */
public record ThreadMetaDto(
        String threadId,
        String title,
        String cwd,
        String status
) {
}
