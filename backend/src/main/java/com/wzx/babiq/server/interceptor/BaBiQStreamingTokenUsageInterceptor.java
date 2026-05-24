package com.wzx.babiq.server.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.StreamingModelInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BaBiQ 流式模型 token 用量拦截器。
 *
 * <p>Spring AI Alibaba 的非流式模型调用会把 usage 写回图状态，现有 {@code BaBiQTokenUsageHook}
 * 可以在 AFTER_MODEL 阶段读取；但流式模型调用为了保证前端边生成边展示，返回的是 {@code Flux<ChatResponse>}，
 * 不会再把最终 usage 放进图状态。本拦截器挂在 SAA 原生 streaming interceptor 扩展点上，
 * 只缓存流式 chunk 里的最新 usage，并在流结束时一次性写入 {@link TurnObservationContext}。</p>
 */
@Component
public final class BaBiQStreamingTokenUsageInterceptor implements StreamingModelInterceptor {

    /**
     * 放在 ModelRequest.context 里的内部键。
     *
     * <p>每次流式订阅都会经过 {@link #beforeStreamCall(ModelRequest)}，因此这里保存的是单次模型流的临时状态，
     * 不放到 Spring 单例字段里，避免多个 turn 并发时互相覆盖。</p>
     */
    private static final String ACCUMULATOR_KEY =
            BaBiQStreamingTokenUsageInterceptor.class.getName() + ".accumulator";

    /**
     * 为本次流式调用准备一个可写 context，并放入 token 用量累积器。
     *
     * @param request SAA 即将发起流式模型调用的请求对象
     * @return 带有本拦截器私有累积器的新请求对象
     */
    @Override
    public ModelRequest beforeStreamCall(ModelRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.getContext() != null) {
            context.putAll(request.getContext());
        }
        context.put(ACCUMULATOR_KEY, new UsageAccumulator());
        return ModelRequest.builder(request)
                .context(context)
                .build();
    }

    /**
     * 读取流式 chunk 中的最新 usage，但暂时不写入 turn 统计。
     *
     * <p>大多数 OpenAI-compatible Provider 会在最后一个 chunk 给出累计 usage。
     * 如果这里每个 chunk 都立即累加，遇到“累计值重复出现”的 Provider 会重复计数；
     * 因此先缓存最新值，等 {@link #afterStreamComplete(AssistantMessage, ModelRequest)} 统一落账。</p>
     *
     * @param response 当前流式模型响应片段
     * @param request 本次流式调用的请求上下文
     * @return 原样返回 response，不改变模型输出内容
     */
    @Override
    public ChatResponse onStreamChunk(ChatResponse response, ModelRequest request) {
        Usage usage = usageOf(response);
        if (usage == null) {
            return response;
        }
        accumulatorOf(request).recordLatest(
                safeToken(usage.getPromptTokens()),
                safeToken(usage.getCompletionTokens()));
        return response;
    }

    /**
     * 流式输出完成后，把最后一次看到的 usage 写入本轮观测上下文。
     *
     * @param message SAA 汇总后的最终 AssistantMessage，这里不需要读取内容
     * @param request 本次流式调用的请求上下文
     */
    @Override
    public void afterStreamComplete(AssistantMessage message, ModelRequest request) {
        UsageAccumulator accumulator = accumulatorOf(request);
        if (!accumulator.hasUsage() || accumulator.isRecorded()) {
            return;
        }
        TurnObservationContext context = turnObservationOf(request);
        if (context != null) {
            context.recordTokens(accumulator.promptTokens(), accumulator.completionTokens());
            accumulator.markRecorded();
        }
    }

    /**
     * 从 ChatResponse metadata 里取 usage。
     *
     * @param response 当前模型响应；为空时说明 Provider 没有产出可解析 chunk
     * @return Provider 返回的 token usage；没有时返回 null
     */
    private Usage usageOf(ChatResponse response) {
        if (response == null) {
            return null;
        }
        ChatResponseMetadata metadata = response.getMetadata();
        return metadata == null ? null : metadata.getUsage();
    }

    /**
     * 从请求 context 中取出本次流的累积器。
     *
     * @param request SAA 流式请求
     * @return beforeStreamCall 放入的 UsageAccumulator
     */
    private UsageAccumulator accumulatorOf(ModelRequest request) {
        Object value = request.getContext().get(ACCUMULATOR_KEY);
        if (value instanceof UsageAccumulator accumulator) {
            return accumulator;
        }
        throw new IllegalStateException("流式 token 用量累积器不存在，请确认 beforeStreamCall 已被调用");
    }

    /**
     * 从请求 context 中取出当前 turn 的观测上下文。
     *
     * @param request SAA 流式请求
     * @return ReActStrategy 写入的 TurnObservationContext；缺失时返回 null，避免非 BaBiQ 上下文的流式调用被拦截器打断
     */
    private TurnObservationContext turnObservationOf(ModelRequest request) {
        Object value = request.getContext().get(TurnObservationContext.METADATA_KEY);
        if (value instanceof TurnObservationContext context) {
            return context;
        }
        return null;
    }

    /**
     * 把 Spring AI 可能返回的 null token 值转换成 0。
     *
     * @param value Provider usage 中的 token 数
     * @return 非空、非负的 token 数
     */
    private long safeToken(Integer value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    /**
     * 单次流式模型调用的 token usage 暂存对象。
     *
     * <p>它只保存在 {@link ModelRequest#getContext()} 里，不跨 turn 复用。
     * {@code recorded} 用于防止 SAA 或测试重复触发 afterStreamComplete 时重复落账。</p>
     */
    private static final class UsageAccumulator {

        /** 最近一次从流式 chunk 里看到的 prompt token。 */
        private long promptTokens;
        /** 最近一次从流式 chunk 里看到的 completion token。 */
        private long completionTokens;
        /** 是否已经看到过有效 usage。 */
        private boolean hasUsage;
        /** 是否已经写入 TurnObservationContext。 */
        private boolean recorded;

        /**
         * 记录当前 chunk 的 usage 快照。
         *
         * @param promptTokens Provider 返回的 prompt token
         * @param completionTokens Provider 返回的 completion token
         */
        void recordLatest(long promptTokens, long completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.hasUsage = true;
        }

        /** @return 最近一次看到的 prompt token。 */
        long promptTokens() {
            return promptTokens;
        }

        /** @return 最近一次看到的 completion token。 */
        long completionTokens() {
            return completionTokens;
        }

        /** @return true 表示至少有一个 chunk 携带过 usage。 */
        boolean hasUsage() {
            return hasUsage;
        }

        /** @return true 表示 usage 已经写入 turn 观测上下文。 */
        boolean isRecorded() {
            return recorded;
        }

        /** 标记 usage 已经落账，避免重复统计。 */
        void markRecorded() {
            this.recorded = true;
        }
    }
}
