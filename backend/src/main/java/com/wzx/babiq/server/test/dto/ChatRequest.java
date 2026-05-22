package com.wzx.babiq.server.test.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * P1-2 临时聊天测试请求。
 *
 * <p>该 DTO 只服务 REST 烟测端点。P1-3 WebSocket Agent Loop 接入后,
 * 正式协议会复用 JSON-RPC 请求体,届时可移除该临时结构。</p>
 *
 * @param text 用户输入文本
 * @param threadId 会话 id,为空时由 controller 回退为 default
 */
public record ChatRequest(
        @NotBlank String text,
        String threadId
) {
}
