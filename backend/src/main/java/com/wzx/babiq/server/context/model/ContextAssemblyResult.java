package com.wzx.babiq.server.context.model;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * ContextAssembler 的输出结果。
 *
 * <p>结果同时包含结构化 envelope、可观测 snapshot 和 Spring AI 消息列表。
 * 这样后续接入 ReactAgent 时可以复用 messages，同时 UI 可读取 snapshot 做解释。</p>
 *
 * @param envelope 分层上下文视图
 * @param snapshot 本轮可见上下文快照
 * @param messages 可直接传入 Spring AI Prompt 的消息列表
 */
public record ContextAssemblyResult(
        ContextEnvelope envelope,
        ContextSnapshot snapshot,
        List<Message> messages
) {

    /**
     * 防御性复制 Spring AI message 列表。
     */
    public ContextAssemblyResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
