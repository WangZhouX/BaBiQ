package com.wzx.babiq.server.api.dto;

/**
 * thread/archive 的响应 DTO。
 *
 * @param ok true 表示归档请求已被后端接受
 * @param threadId 被归档的会话 id
 * @param archived true 表示该会话现在处于软归档状态
 */
public record ThreadArchiveResult(
        boolean ok,
        String threadId,
        boolean archived
) {
}
