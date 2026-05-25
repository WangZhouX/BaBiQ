package org.springframework.ai.openai.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeek V4 thinking mode 专用 OpenAI API 包装器。
 *
 * <p>Spring AI 的 {@link OpenAiApi#chatCompletionStream(ChatCompletionRequest, MultiValueMap)}
 * 会先把 DeepSeek 的 SSE 分片转换成 {@link ChatCompletionChunk}，再交给
 * {@code OpenAiChatModel.internalStream} 判断是否需要执行工具。线上 bug 的根因是：
 * reasoning_content 出现在工具调用前面的分片里，而真正包含 tool_calls 的分片经常不再重复
 * reasoning_content；Spring AI 一看到 tool_calls 就会立刻执行工具并递归发起第二次模型请求，
 * 这时外层 {@code ChatModel.stream().map(...)} 已经来不及补 metadata。</p>
 *
 * <p>Bug 记录（2026-05-25）：用户审批 write_file 后，第二次请求没有回放
 * assistant tool_call 的 reasoning_content，DeepSeek V4 返回 400。这里把补齐逻辑下沉到
 * OpenAiApi 原始流边界，确保 Spring AI 内部工具执行看到的 tool_call chunk 已经携带完整
 * reasoning_content。</p>
 */
public final class DeepSeekV4OpenAiApi extends OpenAiApi {

    /** DeepSeek V4 出站请求形态日志，只记录计数和开关，不记录用户内容、工具参数或密钥。 */
    private static final Logger log = LoggerFactory.getLogger(DeepSeekV4OpenAiApi.class);

    /** DeepSeek 官方 thinking 开关字段名称。 */
    private static final String THINKING_EXTRA_BODY_KEY = "thinking";

    /** DeepSeek 官方 thinking.type 字段名称。 */
    private static final String THINKING_TYPE_KEY = "type";

    /** DeepSeek thinking 关闭值。 */
    private static final String THINKING_DISABLED = "disabled";

    /** 被包装的真实 OpenAI-compatible API 客户端，负责实际 HTTP 请求。 */
    private final OpenAiApi delegate;

    /**
     * 按工具调用指纹缓存 DeepSeek 首轮返回的完整 reasoning_content。
     *
     * <p>HITL 审批恢复时，Spring AI/agent-framework 可能只保留 tool_call 本体，
     * 不再保留 AssistantMessage metadata。这个缓存绑定在同一个 API 包装器实例上，
     * 让暂停前的流式响应和审批后的续轮请求可以在 HTTP 边界重新接上。</p>
     */
    private final Map<String, String> reasoningByToolCallFingerprint = new ConcurrentHashMap<>();

    /**
     * 创建 DeepSeek V4 API 包装器。
     *
     * <p>父类构造参数只用于满足继承结构，聊天请求全部委托给 {@link #delegate}。
     * 这样测试里可以传入 Mockito mock，不依赖 OpenAiApi 内部的 package-private 配置 getter。</p>
     *
     * @param delegate 已绑定 DeepSeek base-url、api-key 和 HTTP 配置的真实客户端
     */
    public DeepSeekV4OpenAiApi(OpenAiApi delegate) {
        super("https://api.deepseek.com",
                new NoopApiKey(),
                new LinkedMultiValueMap<>(),
                "/v1/chat/completions",
                "/v1/embeddings",
                RestClient.builder(),
                WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        this.delegate = delegate;
    }

    /**
     * 非流式聊天请求保持 Spring AI 原始行为。
     *
     * @param chatRequest OpenAI-compatible 请求体
     * @return DeepSeek 返回的完整响应
     */
    @Override
    public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest) {
        return delegate.chatCompletionEntity(chatRequest);
    }

    /**
     * 非流式聊天请求保持 Spring AI 原始行为，并透传额外 HTTP 头。
     *
     * @param chatRequest OpenAI-compatible 请求体
     * @param additionalHttpHeader 调用级额外 HTTP 头
     * @return DeepSeek 返回的完整响应
     */
    @Override
    public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest,
                                                               MultiValueMap<String, String> additionalHttpHeader) {
        return delegate.chatCompletionEntity(chatRequest, additionalHttpHeader);
    }

    /**
     * 流式聊天请求入口，使用空额外头并复用带 header 的实现。
     *
     * @param chatRequest OpenAI-compatible 流式请求体
     * @return reasoning_content 已在工具分片前补齐的流式响应
     */
    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest) {
        return chatCompletionStream(chatRequest, new LinkedMultiValueMap<>());
    }

    /**
     * 流式聊天请求入口，在 Spring AI 看到 chunk 之前补齐 DeepSeek reasoning_content。
     *
     * @param chatRequest OpenAI-compatible 流式请求体
     * @param additionalHttpHeader 调用级额外 HTTP 头
     * @return reasoning_content 已按 choice index 累计并回填的 chunk 流
     */
    @Override
    public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest,
                                                          MultiValueMap<String, String> additionalHttpHeader) {
        ChatCompletionRequest repairedRequest = repairMissingReasoning(chatRequest);
        logRequestShape(repairedRequest);
        DeepSeekReasoningChunkAccumulator accumulator = new DeepSeekReasoningChunkAccumulator();
        return delegate.chatCompletionStream(repairedRequest, additionalHttpHeader)
                .map(accumulator::preserve)
                .doOnNext(this::rememberReasoningFromChunk);
    }

    /**
     * 在真正发出 HTTP 前修补缺失的 reasoning_content。
     *
     * <p>这个位置比 {@code ChatModel#createRequest} 更靠近出站请求，也能覆盖
     * agent-framework 在审批恢复时重新拼装历史消息导致 metadata 丢失的场景。</p>
     *
     * @param request Spring AI 已构造好的 OpenAI-compatible 请求
     * @return 如果命中缓存则返回带 reasoning_content 的新请求，否则返回原请求
     */
    private ChatCompletionRequest repairMissingReasoning(ChatCompletionRequest request) {
        if (request == null || !isThinkingEnabled(request.extraBody()) || CollectionUtils.isEmpty(request.messages())) {
            return request;
        }

        List<ChatCompletionMessage> repairedMessages = new ArrayList<>(request.messages().size());
        boolean changed = false;
        for (ChatCompletionMessage message : request.messages()) {
            if (message.role() != ChatCompletionMessage.Role.ASSISTANT
                    || CollectionUtils.isEmpty(message.toolCalls())
                    || StringUtils.hasText(message.reasoningContent())) {
                repairedMessages.add(message);
                continue;
            }

            String fingerprint = toolCallFingerprint(message.toolCalls());
            String reasoningContent = reasoningByToolCallFingerprint.get(fingerprint);
            if (StringUtils.hasText(reasoningContent)) {
                repairedMessages.add(copyMessageWithReasoning(message, reasoningContent));
                changed = true;
            }
            else {
                repairedMessages.add(message);
            }
        }

        if (!changed) {
            return request;
        }
        log.info("DeepSeek V4 出站请求已从首轮工具调用缓存回填 reasoning_content: model={}", request.model());
        return copyRequestWithMessages(request, List.copyOf(repairedMessages));
    }

    /**
     * 从已经补齐 reasoning 的流式 chunk 中记住工具调用和 reasoning 的对应关系。
     *
     * <p>缓存只使用工具调用 id、type、函数名和参数做指纹，不记录到日志，避免把工具参数暴露到控制台。</p>
     *
     * @param chunk DeepSeek/Spring AI 解析出的流式响应分片
     */
    private void rememberReasoningFromChunk(ChatCompletionChunk chunk) {
        if (chunk == null || CollectionUtils.isEmpty(chunk.choices())) {
            return;
        }
        for (ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
            ChatCompletionMessage delta = choice.delta();
            if (delta == null || CollectionUtils.isEmpty(delta.toolCalls())
                    || !StringUtils.hasText(delta.reasoningContent())) {
                continue;
            }
            reasoningByToolCallFingerprint.put(toolCallFingerprint(delta.toolCalls()), delta.reasoningContent());
        }
    }

    /**
     * 复制 assistant 消息并只替换 reasoning_content。
     *
     * @param message 原始 assistant tool_call 消息
     * @param reasoningContent 从首轮 DeepSeek 流式响应中缓存到的 reasoning_content
     * @return 带 reasoning_content 的新消息
     */
    private static ChatCompletionMessage copyMessageWithReasoning(ChatCompletionMessage message,
                                                                  String reasoningContent) {
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
     * 复制请求并只替换 messages 字段，避免修改 Spring AI 原始不可变 record。
     *
     * @param source 原请求
     * @param messages 修补后的消息列表
     * @return 新的请求 record
     */
    private static ChatCompletionRequest copyRequestWithMessages(ChatCompletionRequest source,
                                                                 List<ChatCompletionMessage> messages) {
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
                source.toolChoice(),
                source.parallelToolCalls(),
                source.user(),
                source.reasoningEffort(),
                source.webSearchOptions(),
                source.verbosity(),
                source.promptCacheKey(),
                source.safetyIdentifier(),
                source.extraBody());
    }

    /**
     * 为同一条工具调用生成稳定指纹，用于把首轮流式 tool_call 与审批后的历史 assistant 对齐。
     *
     * @param toolCalls assistant 消息携带的工具调用列表
     * @return 不可逆但稳定的本地字符串指纹
     */
    private static String toolCallFingerprint(List<ChatCompletionMessage.ToolCall> toolCalls) {
        StringBuilder fingerprint = new StringBuilder();
        for (ChatCompletionMessage.ToolCall toolCall : toolCalls) {
            ChatCompletionMessage.ChatCompletionFunction function = toolCall.function();
            fingerprint.append(nullToEmpty(toolCall.index())).append('\u001F')
                    .append(nullToEmpty(toolCall.id())).append('\u001F')
                    .append(nullToEmpty(toolCall.type())).append('\u001F')
                    .append(function == null ? "" : nullToEmpty(function.name())).append('\u001F')
                    .append(function == null ? "" : nullToEmpty(function.arguments()))
                    .append('\u001E');
        }
        return fingerprint.toString();
    }

    /**
     * 把可空值归一为字符串，保证指纹逻辑不因为 null 抛异常。
     *
     * @param value 可空字段值
     * @return null 对应空字符串，其余值使用 {@link String#valueOf(Object)}
     */
    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 记录 DeepSeek V4 出站请求形态。
     *
     * <p>这里故意只输出模型、消息数量、thinking 开关和 assistant tool_call/reasoning 计数。
     * 如果用户现场再次报 400，这组日志能直接区分“进程没有命中适配器”和
     * “命中了适配器但历史 assistant 缺 reasoning”两类问题，同时避免泄漏 prompt、工具参数或 api-key。</p>
     *
     * @param chatRequest 即将交给底层 OpenAI-compatible 客户端发送的请求体
     */
    private static void logRequestShape(ChatCompletionRequest chatRequest) {
        if (chatRequest == null || !log.isInfoEnabled()) {
            return;
        }
        List<ChatCompletionMessage> messages = chatRequest.messages() == null ? List.of() : chatRequest.messages();
        long assistantToolCallMessages = messages.stream()
                .filter(message -> message.role() == ChatCompletionMessage.Role.ASSISTANT)
                .filter(message -> !CollectionUtils.isEmpty(message.toolCalls()))
                .count();
        long missingReasoningMessages = messages.stream()
                .filter(message -> message.role() == ChatCompletionMessage.Role.ASSISTANT)
                .filter(message -> !CollectionUtils.isEmpty(message.toolCalls()))
                .filter(message -> !StringUtils.hasText(message.reasoningContent()))
                .count();
        boolean thinkingEnabled = isThinkingEnabled(chatRequest.extraBody());
        log.info("DeepSeek V4 出站请求: model={}, messages={}, thinkingEnabled={}, assistantToolCallMessages={}, missingReasoningMessages={}, toolChoicePresent={}",
                chatRequest.model(), messages.size(), thinkingEnabled, assistantToolCallMessages,
                missingReasoningMessages, chatRequest.toolChoice() != null);
        if (missingReasoningMessages > 0) {
            log.warn("DeepSeek V4 出站请求存在缺少 reasoning_content 的 assistant tool_call 历史: model={}, missingReasoningMessages={}",
                    chatRequest.model(), missingReasoningMessages);
        }
    }

    /**
     * 判断请求是否处于 DeepSeek thinking mode。
     *
     * @param extraBody OpenAI-compatible 请求的额外 body 字段
     * @return 未显式设置 thinking.type=disabled 时都按启用处理
     */
    private static boolean isThinkingEnabled(Map<String, Object> extraBody) {
        if (extraBody == null) {
            return true;
        }
        Object thinking = extraBody.get(THINKING_EXTRA_BODY_KEY);
        if (thinking instanceof Map<?, ?> thinkingMap) {
            Object type = thinkingMap.get(THINKING_TYPE_KEY);
            return !(type instanceof String typeText && THINKING_DISABLED.equalsIgnoreCase(typeText.trim()));
        }
        return true;
    }

    /**
     * DeepSeek 原始流分片 reasoning 累计器。
     *
     * <p>同一个响应里可能存在多个 choice，因此按 choice index 分开累计。BaBiQ 当前只使用
     * n=1，但这里保留通用写法，避免以后打开多候选时不同分支的 reasoning 串在一起。</p>
     */
    private static final class DeepSeekReasoningChunkAccumulator {

        /** 按 choice index 保存已经收到的 reasoning_content 片段。 */
        private final Map<Integer, StringBuilder> reasoningByChoice = new LinkedHashMap<>();

        /**
         * 把已累计的 reasoning_content 回填到当前 chunk。
         *
         * @param chunk Spring AI 从 SSE 解析出的原始 chunk
         * @return 如果当前 chunk 缺 reasoning 但前面已经累计过，则返回带 reasoning 的新 chunk
         */
        private ChatCompletionChunk preserve(ChatCompletionChunk chunk) {
            if (chunk == null || chunk.choices() == null || chunk.choices().isEmpty()) {
                return chunk;
            }

            List<ChatCompletionChunk.ChunkChoice> choices = new ArrayList<>(chunk.choices().size());
            boolean changed = false;
            for (ChatCompletionChunk.ChunkChoice choice : chunk.choices()) {
                ChatCompletionMessage delta = choice.delta();
                if (delta == null) {
                    choices.add(choice);
                    continue;
                }

                int choiceIndex = choice.index() == null ? 0 : choice.index();
                StringBuilder reasoningContent = reasoningByChoice.computeIfAbsent(choiceIndex,
                        ignored -> new StringBuilder());

                if (StringUtils.hasText(delta.reasoningContent())) {
                    reasoningContent.append(delta.reasoningContent());
                }

                if (reasoningContent.isEmpty()) {
                    choices.add(choice);
                    continue;
                }

                // 2026-05-25 Bug 修复记录：
                // DeepSeek V4 可能在同一个 tool_call chunk 里继续输出 reasoning_content 片段。
                // Spring AI 的 MessageAggregator 会用后到达的 chunk metadata 覆盖前一个 metadata；
                // 如果这里只放行“当前片段”，最终 AssistantMessage 只会保存最后一小段 reasoning。
                // 因此无论当前 chunk 是否自带 reasoning_content，都统一回填“已累计完整值”。
                choices.add(copyChoiceWithReasoning(choice, delta, reasoningContent.toString()));
                changed = true;
            }

            if (!changed) {
                return chunk;
            }
            return new ChatCompletionChunk(
                    chunk.id(),
                    List.copyOf(choices),
                    chunk.created(),
                    chunk.model(),
                    chunk.serviceTier(),
                    chunk.systemFingerprint(),
                    chunk.object(),
                    chunk.usage());
        }

        /**
         * 复制当前 choice，并只替换 delta 里的 reasoning_content 字段。
         *
         * @param choice 原始 choice
         * @param delta 原始 delta 消息
         * @param reasoningContent 当前 choice 已累计出的完整 reasoning_content
         * @return 带完整 reasoning_content 的新 choice
         */
        private ChatCompletionChunk.ChunkChoice copyChoiceWithReasoning(ChatCompletionChunk.ChunkChoice choice,
                                                                        ChatCompletionMessage delta,
                                                                        String reasoningContent) {
            ChatCompletionMessage preservedDelta = new ChatCompletionMessage(
                    delta.rawContent(),
                    delta.role(),
                    delta.name(),
                    delta.toolCallId(),
                    delta.toolCalls(),
                    delta.refusal(),
                    delta.audioOutput(),
                    delta.annotations(),
                    reasoningContent);
            return new ChatCompletionChunk.ChunkChoice(choice.finishReason(), choice.index(), preservedDelta,
                    choice.logprobs());
        }
    }
}
