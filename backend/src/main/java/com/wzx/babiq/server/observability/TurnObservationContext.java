package com.wzx.babiq.server.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * 单个 turn 的观测上下文。
 */
public final class TurnObservationContext {

    public static final String METADATA_KEY = "babiq.turnObservation";

    private final String threadId;
    private final String turnId;
    private final String providerId;
    private final String model;
    private final long startedNanos;
    private final LongSupplier nanoTime;
    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> toolCallsByName = new ConcurrentHashMap<>();

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

    public long durationMs() {
        long elapsed = nanoTime.getAsLong() - startedNanos;
        return Math.max(0L, elapsed / 1_000_000L);
    }
}
