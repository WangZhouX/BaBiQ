package org.springframework.ai.openai.api;

import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
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

    /** 被包装的真实 OpenAI-compatible API 客户端，负责实际 HTTP 请求。 */
    private final OpenAiApi delegate;

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
        DeepSeekReasoningChunkAccumulator accumulator = new DeepSeekReasoningChunkAccumulator();
        return delegate.chatCompletionStream(chatRequest, additionalHttpHeader)
                .map(accumulator::preserve);
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
