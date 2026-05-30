package com.wzx.babiq.server.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.DeepSeekV4OpenAiChatModel;

import java.util.Map;
import java.util.Optional;

/**
 * 从 Spring AI AssistantMessage 中提取可展示的 reasoning 内容。
 *
 * <p>供应商 thinking/reasoning 字段不是普通回答正文，Spring AI 会把它放进
 * AssistantMessage metadata。BaBiQ 只把它作为桌面端展示 item 使用，并在这里统一做
 * 空值过滤和长度截断，避免超长思考过程撑爆 SQLite、WebSocket 或 UI。</p>
 */
final class ReasoningContentSupport {

    /** 单条 reasoning 展示文本的最大字符数；超过后保留前缀并追加截断提示。 */
    private static final int MAX_REASONING_CHARS = 12_000;

    private ReasoningContentSupport() {
    }

    /**
     * 提取适合展示和持久化的 reasoning 文本。
     *
     * @param assistantMessage Spring AI 聚合后的助手消息或流式 chunk 消息
     * @return 过滤空白并截断后的文本；不存在 thinking/reasoning 时为空
     */
    static Optional<String> extractDisplayText(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return Optional.empty();
        }
        Map<String, Object> metadata = assistantMessage.getMetadata();
        Object value = metadata.get(DeepSeekV4OpenAiChatModel.REASONING_METADATA_KEY);
        if (!(value instanceof String text) || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(truncate(text.strip()));
    }

    /**
     * 对 reasoning 做展示级截断。
     *
     * <p>这里按字符数截断而不是 token，是因为该字段不会进入模型上下文，只需要保护本地
     * 协议和 UI。真正的上下文预算仍由 P3 的 ContextTokenEstimator 负责。</p>
     */
    private static String truncate(String text) {
        if (text.length() <= MAX_REASONING_CHARS) {
            return text;
        }
        String marker = "\n\n[思考过程过长，已截断；原始长度 " + text.length() + " 字符]";
        int prefixLength = Math.max(0, MAX_REASONING_CHARS - marker.length());
        return text.substring(0, prefixLength) + marker;
    }
}
