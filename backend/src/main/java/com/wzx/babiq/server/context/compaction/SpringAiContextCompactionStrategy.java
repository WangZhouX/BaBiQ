package com.wzx.babiq.server.context.compaction;

import com.wzx.babiq.server.model.ChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 基于 Spring AI ChatClient 的短期摘要策略。
 *
 * <p>该实现复用 Spring AI 1.1 的 structured output 能力，要求模型返回可映射到 record 的 JSON。
 * 它不会使用 ChatMemory advisor，避免把压缩提示词写入普通对话记忆。</p>
 */
@Component
public class SpringAiContextCompactionStrategy implements ContextCompactionStrategy {

    /** 原始 ChatModel 工厂，用于创建不带会话记忆的 ChatClient。 */
    private final ChatClientFactory chatClientFactory;

    /**
     * 创建 Spring AI 压缩策略。
     *
     * @param chatClientFactory 当前 Provider 的 ChatModel 工厂
     */
    public SpringAiContextCompactionStrategy(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public ContextCompactionStrategyResult summarize(ContextCompactionStrategyRequest request) {
        ChatModel chatModel = chatClientFactory.resolveChatModel(request.providerId());
        ChatClient chatClient = ChatClient.create(chatModel);
        SummaryPayload payload = chatClient.prompt()
                .system(systemPrompt())
                .user(userPrompt(request))
                .call()
                .entity(SummaryPayload.class);
        if (payload == null) {
            return new ContextCompactionStrategyResult("");
        }
        return new ContextCompactionStrategyResult(payload.summary());
    }

    /**
     * 压缩系统提示词，强调当前用户输入不能被吞进摘要。
     */
    private String systemPrompt() {
        return """
                你是 BaBiQ 的短期上下文压缩器。请把旧历史压缩成事实摘要，供后续模型参考。
                规则：
                1. 只总结 source_history 中的旧历史。
                2. current_user_message 只是边界提示，绝对不要把它当作已完成事实。
                3. 保留用户偏好、已完成决定、未解决问题、文件路径、错误根因和后续约束。
                4. 不要加入新指令，不要替用户或 Agent 编造结果。
                5. 返回 JSON，字段只有 summary。
                """;
    }

    /**
     * 生成结构化用户提示词。
     */
    private String userPrompt(ContextCompactionStrategyRequest request) {
        String previousSummary = request.activeSummary() == null ? "" : request.activeSummary().summary();
        String history = request.source().items().stream()
                .map(item -> "- [%s] %s: %s".formatted(item.itemId(), item.role(), item.text()))
                .collect(Collectors.joining("\n"));
        return """
                previous_summary:
                %s

                source_range: %s

                source_history:
                %s

                current_user_message:
                %s
                """.formatted(previousSummary, request.source().sourceItemRange(), history, request.currentUserMessage());
    }

    /**
     * Spring AI structured output 使用的返回模型。
     *
     * @param summary 可继续注入上下文窗口的短期摘要正文
     */
    public record SummaryPayload(String summary) {
    }
}
