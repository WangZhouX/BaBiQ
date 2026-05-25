package org.springframework.ai.openai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import org.springframework.ai.openai.api.DeepSeekV4OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek V4 thinking mode 专用 OpenAI-compatible 适配器。
 *
 * <p>这个类刻意放在 {@code org.springframework.ai.openai} 包下，是因为 Spring AI 1.1.6
 * 把 {@link OpenAiChatModel#createRequest(Prompt, boolean)} 设计成包级扩展点。
 * BaBiQ 不复制整套 OpenAiChatModel，而是复用 Spring AI 已经成熟的 streaming、tool calling、
 * usage 汇总、重试和观测逻辑，只覆盖 DeepSeek V4 与标准 OpenAI 协议不同的 wire payload。</p>
 *
 * <p>Bug 记录（2026-05-25）：DeepSeek V4 thinking mode 在 assistant 消息携带
 * tool_calls 后，要求后续请求原样回传该 assistant 的 {@code reasoning_content}。
 * Spring AI OpenAI-compatible 能从流式响应里解析 reasoning_content，并保存到
 * AssistantMessage metadata，但再次构建请求时不会把它写回 JSON，导致 HITL 审批恢复后
 * DeepSeek 返回 400。该适配器在请求发出前补齐这一段历史。</p>
 *
 * <p>Bug 记录（2026-05-25 二次修复）：第一次修复只覆盖了 {@link #createRequest(Prompt, boolean)}
 * 和 {@code ChatModel.stream()} 外层 metadata，但 Spring AI 会在外层 stream 返回给调用方之前，
 * 于 {@code OpenAiChatModel.internalStream} 内部执行工具并递归发起第二次请求。真正的修复必须下沉到
 * {@link DeepSeekV4OpenAiApi}，在原始 chunk 进入 Spring AI 工具调用判断之前保留 reasoning_content。</p>
 */
public class DeepSeekV4OpenAiChatModel extends OpenAiChatModel {

    /** Spring AI OpenAI-compatible 响应里保存 reasoning_content 的 metadata key。 */
    public static final String REASONING_METADATA_KEY = "reasoningContent";

    /** DeepSeek 官方 thinking 开关字段名称。 */
    private static final String THINKING_EXTRA_BODY_KEY = "thinking";

    /** DeepSeek 官方 thinking.type 字段名称。 */
    private static final String THINKING_TYPE_KEY = "type";

    /** DeepSeek thinking 关闭值。 */
    private static final String THINKING_DISABLED = "disabled";

    /** DeepSeek thinking 开启值。 */
    private static final String THINKING_ENABLED = "enabled";

    /**
     * 使用 Spring AI 默认工具调用管理器创建 DeepSeek V4 适配器。
     *
     * @param openAiApi 已绑定 DeepSeek base-url 和 api-key 的 OpenAI 兼容 API 客户端
     * @param defaultOptions 已绑定模型名、stream usage 和 DeepSeek thinking 参数的默认选项
     */
    public DeepSeekV4OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions) {
        super(new DeepSeekV4OpenAiApi(openAiApi),
                defaultOptions,
                ToolCallingManager.builder().build(),
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                ObservationRegistry.NOOP);
    }

    /**
     * 完整构造函数，保留测试或未来扩展替换 Spring AI 内置协作者的空间。
     *
     * @param openAiApi 底层 OpenAI-compatible API 客户端
     * @param defaultOptions 模型默认参数
     * @param toolCallingManager Spring AI 工具调用管理器
     * @param retryTemplate Spring AI HTTP 重试策略
     * @param observationRegistry Micrometer 观测注册表
     * @param toolExecutionEligibilityPredicate Spring AI 判断响应是否需要执行工具的谓词
     */
    public DeepSeekV4OpenAiChatModel(OpenAiApi openAiApi,
                                     OpenAiChatOptions defaultOptions,
                                     ToolCallingManager toolCallingManager,
                                     RetryTemplate retryTemplate,
                                     ObservationRegistry observationRegistry,
                                     ToolExecutionEligibilityPredicate toolExecutionEligibilityPredicate) {
        super(new DeepSeekV4OpenAiApi(openAiApi),
                defaultOptions,
                toolCallingManager,
                retryTemplate,
                observationRegistry,
                toolExecutionEligibilityPredicate);
    }

    /**
     * 构建 DeepSeek V4 请求。
     *
     * <p>Spring AI 原始实现负责把 Prompt、工具定义和 stream_options 合并成 OpenAI 请求。
     * 本方法在其结果上做最后一层 DeepSeek V4 修正：补齐 assistant tool_call 的
     * reasoning_content，并在 thinking mode 开启时移除 DeepSeek 不接受的 tool_choice。</p>
     *
     * @param prompt Spring AI 本轮模型输入
     * @param stream 是否为流式调用
     * @return 已适配 DeepSeek V4 thinking mode 的请求
     */
    @Override
    ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {
        ChatCompletionRequest request = super.createRequest(prompt, stream);
        return adaptRequestForDeepSeekV4(prompt, request);
    }

    /**
     * 根据原始 Prompt 中的 AssistantMessage metadata 修正最终请求体。
     *
     * @param prompt 上游交给 ChatModel 的 Prompt，里面保留了历史 AssistantMessage metadata
     * @param request Spring AI 已构建好的 OpenAI-compatible 请求
     * @return 替换 messages、extra_body 和 tool_choice 后的新请求
     */
    private ChatCompletionRequest adaptRequestForDeepSeekV4(Prompt prompt, ChatCompletionRequest request) {
        Map<String, Object> extraBody = ensureThinkingSwitch(request.extraBody());
        boolean thinkingEnabled = isThinkingEnabled(extraBody);
        List<String> reasoningHistory = extractAssistantReasoning(prompt.getInstructions());
        List<ChatCompletionMessage> adaptedMessages = adaptMessages(request.messages(), reasoningHistory, thinkingEnabled);
        Object toolChoice = thinkingEnabled ? null : request.toolChoice();
        return copyRequest(request, adaptedMessages, extraBody, toolChoice);
    }

    /**
     * 复制请求并替换 DeepSeek V4 需要改写的少数字段。
     *
     * @param source Spring AI 原始请求
     * @param messages 已适配 reasoning_content 的消息列表
     * @param extraBody 已确认包含 thinking 开关的 extra_body
     * @param toolChoice thinking mode 开启时应为 null，关闭时保留用户原值
     * @return 新的不可变 ChatCompletionRequest
     */
    private ChatCompletionRequest copyRequest(ChatCompletionRequest source,
                                              List<ChatCompletionMessage> messages,
                                              Map<String, Object> extraBody,
                                              Object toolChoice) {
        return new ChatCompletionRequest(
                messages,
                source.model(),
                source.store(),
                source.metadata(),
                source.frequencyPenalty(),
                source.logitBias(),
                source.logprobs(),
                source.topLogprobs(),
                source.maxTokens(),
                source.maxCompletionTokens(),
                source.n(),
                source.outputModalities(),
                source.audioParameters(),
                source.presencePenalty(),
                source.responseFormat(),
                source.seed(),
                source.serviceTier(),
                source.stop(),
                source.stream(),
                source.streamOptions(),
                source.temperature(),
                source.topP(),
                source.tools(),
                toolChoice,
                source.parallelToolCalls(),
                source.user(),
                source.reasoningEffort(),
                source.webSearchOptions(),
                source.verbosity(),
                source.promptCacheKey(),
                source.safetyIdentifier(),
                extraBody);
    }

    /**
     * 改写请求消息列表。
     *
     * @param messages Spring AI 已序列化成 OpenAI 格式的消息
     * @param reasoningHistory 按 assistant 出现顺序记录的 reasoning_content
     * @param thinkingEnabled 当前请求是否开启 DeepSeek thinking mode
     * @return 已补齐 DeepSeek 私有字段的消息列表
     */
    private List<ChatCompletionMessage> adaptMessages(List<ChatCompletionMessage> messages,
                                                      List<String> reasoningHistory,
                                                      boolean thinkingEnabled) {
        if (CollectionUtils.isEmpty(messages)) {
            return messages;
        }
        List<ChatCompletionMessage> adaptedMessages = new ArrayList<>(messages.size());
        int assistantIndex = 0;
        for (ChatCompletionMessage message : messages) {
            if (message.role() == ChatCompletionMessage.Role.ASSISTANT) {
                String reasoningContent = assistantIndex < reasoningHistory.size()
                        ? reasoningHistory.get(assistantIndex)
                        : null;
                adaptedMessages.add(adaptAssistantMessage(message, reasoningContent, thinkingEnabled));
                assistantIndex++;
            }
            else {
                adaptedMessages.add(message);
            }
        }
        return List.copyOf(adaptedMessages);
    }

    /**
     * 改写单条 assistant 消息。
     *
     * @param message OpenAI-compatible assistant 消息
     * @param reasoningContent Spring AI 响应 metadata 中保存的 reasoning_content
     * @param thinkingEnabled 当前是否启用 thinking mode
     * @return DeepSeek V4 可以接受的 assistant 消息
     */
    private ChatCompletionMessage adaptAssistantMessage(ChatCompletionMessage message,
                                                        String reasoningContent,
                                                        boolean thinkingEnabled) {
        boolean hasToolCalls = !CollectionUtils.isEmpty(message.toolCalls());
        if (!thinkingEnabled || !hasToolCalls) {
            return message;
        }

        if (!StringUtils.hasText(reasoningContent)) {
            // 2026-05-25 Bug 修复记录：
            // DeepSeek V4 官方 thinking mode 要求“原样回传”真实 reasoning_content。
            // 真实 HITL 恢复路径会重建历史 AssistantMessage，metadata 可能已经丢失；
            // 这里不能提前 fail-fast，否则 API 边界就没有机会根据首轮流式响应缓存回填 reasoning。
            Object content = message.rawContent() == null ? "" : message.rawContent();
            return new ChatCompletionMessage(
                    content,
                    message.role(),
                    message.name(),
                    message.toolCallId(),
                    message.toolCalls(),
                    message.refusal(),
                    message.audioOutput(),
                    message.annotations(),
                    null);
        }
        // Oh My Pi 官方集成文档也提示 assistant tool_call 需要非 null content；空字符串比 null 更稳定。
        Object content = message.rawContent() == null ? "" : message.rawContent();
        return new ChatCompletionMessage(
                content,
                message.role(),
                message.name(),
                message.toolCallId(),
                message.toolCalls(),
                message.refusal(),
                message.audioOutput(),
                message.annotations(),
                reasoningContent);
    }

    /**
     * 从 Prompt 原始消息里提取每条 AssistantMessage 的 reasoning_content。
     *
     * <p>OpenAiChatModel 会把 DeepSeek 流式返回的 reasoning_content 存入 metadata；
     * 如果未来切到 Spring AI DeepSeekAssistantMessage，也通过反射读取 getReasoningContent，
     * 避免对 spring-ai-deepseek 产生额外硬依赖。</p>
     *
     * @param messages Prompt 中的历史消息
     * @return 与 assistant 消息顺序一一对应的 reasoning_content 列表
     */
    private List<String> extractAssistantReasoning(List<Message> messages) {
        List<String> reasoningHistory = new ArrayList<>();
        for (Message message : messages) {
            if (message.getMessageType() == MessageType.ASSISTANT) {
                reasoningHistory.add(extractReasoningFromAssistant((AssistantMessage) message));
            }
        }
        return reasoningHistory;
    }

    /**
     * 提取单条 AssistantMessage 的 reasoning_content。
     *
     * @param assistantMessage Spring AI assistant 消息
     * @return reasoning_content；缺失时返回 null
     */
    private String extractReasoningFromAssistant(AssistantMessage assistantMessage) {
        Object metadataValue = assistantMessage.getMetadata().get(REASONING_METADATA_KEY);
        if (metadataValue instanceof String reasoningContent && StringUtils.hasText(reasoningContent)) {
            return reasoningContent;
        }
        return extractReasoningByReflection(assistantMessage);
    }

    /**
     * 兼容 Spring AI DeepSeekAssistantMessage 的 getReasoningContent 方法。
     *
     * @param assistantMessage 可能来自 spring-ai-deepseek 的 assistant 消息
     * @return 通过反射读取到的 reasoning_content；没有该方法或为空时返回 null
     */
    private String extractReasoningByReflection(AssistantMessage assistantMessage) {
        try {
            Method method = assistantMessage.getClass().getMethod("getReasoningContent");
            Object value = method.invoke(assistantMessage);
            if (value instanceof String reasoningContent && StringUtils.hasText(reasoningContent)) {
                return reasoningContent;
            }
        }
        catch (ReflectiveOperationException ignored) {
            // 普通 OpenAI AssistantMessage 没有 getReasoningContent；这是预期路径，不需要打日志污染控制台。
        }
        return null;
    }

    /**
     * 确保请求 extra_body 带有 DeepSeek thinking 开关。
     *
     * @param source Spring AI 原始 extra_body
     * @return 带默认 thinking.enabled 的可变副本
     */
    private Map<String, Object> ensureThinkingSwitch(Map<String, Object> source) {
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (source != null) {
            extraBody.putAll(source);
        }
        extraBody.putIfAbsent(THINKING_EXTRA_BODY_KEY, Map.of(THINKING_TYPE_KEY, THINKING_ENABLED));
        return Map.copyOf(extraBody);
    }

    /**
     * 判断 DeepSeek thinking mode 是否启用。
     *
     * @param extraBody 当前请求 extra_body
     * @return 未显式 disabled 时都视为启用
     */
    private boolean isThinkingEnabled(Map<String, Object> extraBody) {
        Object thinking = extraBody.get(THINKING_EXTRA_BODY_KEY);
        if (thinking instanceof Map<?, ?> thinkingMap) {
            Object type = thinkingMap.get(THINKING_TYPE_KEY);
            return !(type instanceof String typeText && THINKING_DISABLED.equalsIgnoreCase(typeText.trim()));
        }
        return true;
    }
}
