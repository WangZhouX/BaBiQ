package com.wzx.babiq.server.memory;

import com.wzx.babiq.server.memory.repository.MemoryCandidateRecord;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI structured output 的长期记忆归并策略。
 *
 * <p>实现方式与 P3-3 短期压缩一致：从 ChatClientFactory 取裸 ChatModel，
 * 再用 ChatClient.create(chatModel) 发起一次无 ChatMemory advisor 的模型调用，
 * 避免归并提示词污染普通对话窗口。</p>
 */
@Component
public class SpringAiStructuredMemoryConsolidationStrategy implements MemoryConsolidationStrategy {

    /** ChatModel 工厂。 */
    private final ChatClientFactory chatClientFactory;

    /**
     * 创建 Spring AI 归并策略。
     */
    public SpringAiStructuredMemoryConsolidationStrategy(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public String generateMemorySummary(List<MemoryCandidateRecord> candidates) {
        MemoryPayload payload = callModel("生成 4000 token 以内的 dense memory_summary.md。", candidates);
        return payload == null ? fallbackSummary(candidates) : payload.content();
    }

    @Override
    public String generateMemoryHandbook(List<MemoryCandidateRecord> candidates) {
        MemoryPayload payload = callModel("生成按主题索引的 MEMORY.md，适合人工阅读和检索。", candidates);
        return payload == null ? fallbackSummary(candidates) : payload.content();
    }

    private MemoryPayload callModel(String task, List<MemoryCandidateRecord> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new MemoryPayload("");
        }
        ChatModel chatModel = chatClientFactory.resolveChatModel(null);
        ChatClient chatClient = ChatClient.create(chatModel);
        return chatClient.prompt()
                .system("""
                        你是 BaBiQ 的长期记忆归并器。只基于候选内容生成文件正文。
                        不要加入候选之外的事实，不要保留密钥，不要输出 Markdown 代码围栏。
                        返回 JSON，字段只有 content。
                        """)
                .user(task + "\n\n候选:\n" + candidatesPrompt(candidates))
                .call()
                .entity(MemoryPayload.class);
    }

    private static String candidatesPrompt(List<MemoryCandidateRecord> candidates) {
        return candidates.stream()
                .map(candidate -> "## %s\n%s".formatted(candidate.candidateId(), candidate.rawMemory()))
                .collect(Collectors.joining("\n\n"));
    }

    private static String fallbackSummary(List<MemoryCandidateRecord> candidates) {
        return candidates.stream()
                .map(MemoryCandidateRecord::rawMemory)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Spring AI structured output 返回模型。
     *
     * @param content 文件正文
     */
    public record MemoryPayload(String content) {
    }
}
