package com.wzx.babiq.server.test.dto;

/**
 * P1-2 临时聊天测试响应。
 *
 * <p>响应中显式带回 providerId、model 和 threadId,便于人工烟测确认请求
 * 实际打到了哪个 provider,以及跨轮记忆是否使用同一个 conversationId。</p>
 *
 * @param providerId 实际使用的 provider id
 * @param model 实际使用的模型名
 * @param threadId 实际使用的会话 id
 * @param reply 模型返回文本
 */
public record ChatResponse(
        String providerId,
        String model,
        String threadId,
        String reply
) {
}
