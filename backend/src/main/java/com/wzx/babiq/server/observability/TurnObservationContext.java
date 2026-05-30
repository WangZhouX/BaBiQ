package com.wzx.babiq.server.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 单个 turn 的观测上下文。
 */
public final class TurnObservationContext {

    public static final String METADATA_KEY = "babiq.turnObservation";

    /** 对话线程 id，用于结构化日志和 turnSummary 归属。 */
    private final String threadId;
    /** 当前执行轮次 id，是观测上下文在 registry 中的主键。 */
    private final String turnId;
    /** 本轮使用的 provider id；为空时表示使用后端 active provider。 */
    private final String providerId;
    /** 实际调用的模型名称，运行反馈条和日志都会展示它。 */
    private final String model;
    /** turn 开始时的纳秒时间戳，用 nanoTime 计算耗时可以避免系统时钟回拨影响。 */
    private final long startedNanos;
    /** 可注入的纳秒时钟，测试里可以替换成可控时间源。 */
    private final LongSupplier nanoTime;
    /** 累计 prompt token；LongAdder 适合被 hook 和 worker 线程并发递增。 */
    private final LongAdder promptTokens = new LongAdder();
    /** 累计 completion token，用于最终 turnSummary 和本地统计。 */
    private final LongAdder completionTokens = new LongAdder();
    /** 工具名 -> 调用次数，用于 turnSummary 的工具数量和后端指标。 */
    private final ConcurrentHashMap<String, LongAdder> toolCallsByName = new ConcurrentHashMap<>();
    /** 当前 turn 内最新 plan item id；update_plan 首次新增、后续更新同一条 item 时读取它。 */
    private final AtomicReference<String> planItemId = new AtomicReference<>();

    private TurnObservationContext(String threadId,
                                   String turnId,
                                   String providerId,
                                   String model,
                                   LongSupplier nanoTime) {
        this.threadId = threadId;
        this.turnId = turnId;
        this.providerId = providerId == null ? "_active" : providerId;
        this.model = model == null || model.isBlank() ? "_unknown" : model;
        this.nanoTime = nanoTime;
        this.startedNanos = nanoTime.getAsLong();
    }

    public static TurnObservationContext start(String threadId, String turnId, String providerId, String model) {
        return start(threadId, turnId, providerId, model, System::nanoTime);
    }

    public static TurnObservationContext start(String threadId,
                                               String turnId,
                                               String providerId,
                                               String model,
                                               LongSupplier nanoTime) {
        return new TurnObservationContext(threadId, turnId, providerId, model, nanoTime);
    }

    public void recordTokens(long prompt, long completion) {
        promptTokens.add(Math.max(0L, prompt));
        completionTokens.add(Math.max(0L, completion));
    }

    public void recordToolCall(String toolName) {
        String key = toolName == null || toolName.isBlank() ? "_unknown" : toolName;
        toolCallsByName.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    public String threadId() {
        return threadId;
    }

    public String turnId() {
        return turnId;
    }

    public String providerId() {
        return providerId;
    }

    public String model() {
        return model;
    }

    public long promptTokens() {
        return promptTokens.sum();
    }

    public long completionTokens() {
        return completionTokens.sum();
    }

    public long totalTokens() {
        return promptTokens() + completionTokens();
    }

    public int toolCalls() {
        return Math.toIntExact(toolCallsByName.values().stream().mapToLong(LongAdder::sum).sum());
    }

    public Map<String, Long> toolCallsByName() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        toolCallsByName.forEach((name, count) -> snapshot.put(name, count.sum()));
        return Map.copyOf(snapshot);
    }

    /**
     * 返回当前 turn 已经创建过的 plan item id。
     *
     * <p>它只服务 P4 的 update_plan 工具：plan 是运行中 UI 状态，不需要新数据库表；
     * 同一 turn 内复用 item id 即可让桌面端做 item/updated 原地刷新。</p>
     */
    public String planItemId() {
        return planItemId.get();
    }

    /**
     * 记录当前 turn 的 plan item id，只允许第一次设置成功。
     *
     * @param itemId 首次 update_plan 生成的 item id
     * @return 实际应该使用的 item id；并发重复设置时返回已存在值
     */
    public String rememberPlanItemId(String itemId) {
        planItemId.compareAndSet(null, itemId);
        return planItemId.get();
    }

    public long durationMs() {
        long elapsed = nanoTime.getAsLong() - startedNanos;
        return Math.max(0L, elapsed / 1_000_000L);
    }
}
