package com.wzx.babiq.server.observability;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存跨 HITL 中断/恢复的 turn 观测上下文。
 */
@Component
public class TurnObservationRegistry {

    private final ConcurrentHashMap<String, TurnObservationContext> contexts = new ConcurrentHashMap<>();

    public TurnObservationContext start(String threadId, String turnId, String providerId, String model) {
        TurnObservationContext context = TurnObservationContext.start(threadId, turnId, providerId, model);
        contexts.put(turnId, context);
        return context;
    }

    public TurnObservationContext getOrStart(String threadId, String turnId, String providerId, String model) {
        return contexts.computeIfAbsent(turnId,
                ignored -> TurnObservationContext.start(threadId, turnId, providerId, model));
    }

    public void remove(String turnId) {
        contexts.remove(turnId);
    }
}
