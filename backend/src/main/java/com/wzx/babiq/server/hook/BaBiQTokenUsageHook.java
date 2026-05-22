package com.wzx.babiq.server.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

/**
 * 模型 token 用量累计 Hook。
 *
 * <p>这是 P1-3a 保留的唯一自写 ModelHook：D21 要求横切关注点离开 AgentLoop，
 * 因此每次模型调用后的 Usage 由本类累计。P1-3a 只记录 prompt/completion token，
 * P1-3b 再结合 ModelMetadata 计算成本并展示。</p>
 */
@Component
@HookPositions({HookPosition.AFTER_MODEL})
public final class BaBiQTokenUsageHook extends ModelHook {

    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();

    /**
     * 返回 Hook 名称，供 SAA 图节点命名和调试定位。
     *
     * @return Hook 名称
     */
    @Override
    public String getName() {
        return "babiq_token_usage";
    }

    /**
     * SAA AFTER_MODEL 回调。
     *
     * @param state 当前图状态
     * @param config 当前运行配置
     * @return 空更新，表示不修改图状态
     */
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        for (Object value : state.data().values()) {
            if (value instanceof Usage usage) {
                record(usage);
                break;
            }
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * 按 Spring AI Usage 记录一次模型调用。
     *
     * @param usage Spring AI token 使用量对象
     */
    public void record(Usage usage) {
        if (usage == null) {
            return;
        }
        record(safeToken(usage.getPromptTokens()), safeToken(usage.getCompletionTokens()));
    }

    /**
     * 记录一次模型调用 token。
     *
     * @param prompt 本次 prompt token 数，必须非负
     * @param completion 本次 completion token 数，必须非负
     */
    public void record(long prompt, long completion) {
        if (prompt < 0 || completion < 0) {
            throw new IllegalArgumentException("token 数不能为负数");
        }
        promptTokens.add(prompt);
        completionTokens.add(completion);
    }

    /**
     * 返回 prompt token 累计值。
     *
     * @return prompt token 累计值
     */
    public long totalPromptTokens() {
        return promptTokens.sum();
    }

    /**
     * 返回 completion token 累计值。
     *
     * @return completion token 累计值
     */
    public long totalCompletionTokens() {
        return completionTokens.sum();
    }

    /**
     * 获取不可变快照。
     *
     * @return 当前累计值快照
     */
    public Snapshot snapshot() {
        return new Snapshot(totalPromptTokens(), totalCompletionTokens());
    }

    /**
     * 清空累计值，供新 turn 开始时复用同一个 Hook bean。
     */
    public void reset() {
        promptTokens.reset();
        completionTokens.reset();
    }

    private long safeToken(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    /**
     * token 累计快照。
     *
     * @param promptTokens prompt token 数
     * @param completionTokens completion token 数
     */
    public record Snapshot(long promptTokens, long completionTokens) {

        /**
         * 返回总 token 数。
         *
         * @return prompt + completion
         */
        public long total() {
            return promptTokens + completionTokens;
        }
    }
}
