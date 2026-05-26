package com.wzx.babiq.server.memory.extract;

import com.wzx.babiq.server.conversation.repository.ItemRecord;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Spring AI structured output 的 Phase1 抽取器。
 *
 * <p>实现思路与 P3-3 短期压缩一致：通过 {@link ChatClientFactory#resolveChatModel(String)} 获取原始
 * ChatModel，再用 {@link ChatClient#create(ChatModel)} 发起无 ChatMemory advisor 的一次性结构化调用。
 * 这样抽取提示词不会进入普通聊天记忆，也不会绕过 BaBiQ 的 SQLite 审计流水线。</p>
 */
@Component
public class SpringAiMemoryStageOneExtractor implements MemoryStageOneExtractor {

    /** ChatModel 工厂，负责使用当前 active provider 构建 Spring AI 模型对象。 */
    private final ChatClientFactory chatClientFactory;

    /**
     * 创建 Spring AI Phase1 抽取器。
     *
     * @param chatClientFactory 模型工厂，允许后续按 Provider 配置切换模型
     */
    public SpringAiMemoryStageOneExtractor(ChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public MemoryStageOneResult extract(MemoryStageOneRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            return MemoryStageOneResult.empty();
        }
        ChatModel chatModel = chatClientFactory.resolveChatModel(request.providerId());
        ChatClient chatClient = ChatClient.create(chatModel);
        StageOnePayload payload = chatClient.prompt()
                .system(systemPrompt())
                .user(userPrompt(request))
                .call()
                .entity(StageOnePayload.class);
        if (payload == null || isBlank(payload.rawMemory()) && isBlank(payload.rolloutSummary())) {
            return MemoryStageOneResult.empty();
        }
        List<String> sourceItemIds = payload.sourceItemIds() == null || payload.sourceItemIds().isEmpty()
                ? request.items().stream().map(ItemRecord::itemId).toList()
                : payload.sourceItemIds();
        return new MemoryStageOneResult(
                payload.rawMemory(),
                payload.rolloutSummary(),
                isBlank(payload.rolloutSlug()) ? request.threadId() : payload.rolloutSlug(),
                sourceItemIds);
    }

    private String systemPrompt() {
        return """
                你是 BaBiQ 的长期记忆 Phase1 抽取器。只从 source_items 中提炼未来仍有价值的事实。
                规则：
                1. 抽取用户长期偏好、项目约束、已确认决策、稳定路径、反复出现的故障根因和后续边界。
                2. 不要把临时寒暄、一次性中间输出、模型自己的猜测写入长期记忆。
                3. 不要保留 API Key、token、密码、私钥；如果输入里出现敏感信息，输出前自行删除。
                4. 返回 JSON，字段只有 rawMemory、rolloutSummary、rolloutSlug、sourceItemIds。
                """;
    }

    private String userPrompt(MemoryStageOneRequest request) {
        String items = request.items().stream()
                .map(item -> "- [%s] type=%s status=%s payload=%s".formatted(
                        item.itemId(), item.type(), item.status(), item.payloadJson()))
                .collect(Collectors.joining("\n"));
        return """
                thread_id: %s
                cwd: %s
                token_budget: %d

                source_items:
                %s
                """.formatted(request.threadId(), request.cwd(), request.tokenBudget(), items);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * Spring AI structured output 返回模型。
     *
     * @param rawMemory 长期记忆候选正文
     * @param rolloutSummary 人工审计摘要
     * @param rolloutSlug rollout 文件短标识
     * @param sourceItemIds 模型认为参与抽取的 item id
     */
    public record StageOnePayload(
            String rawMemory,
            String rolloutSummary,
            String rolloutSlug,
            List<String> sourceItemIds
    ) {
    }
}
