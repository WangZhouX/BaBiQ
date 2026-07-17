package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存跨 HITL 中断/恢复的 turn 观测上下文。
 */
@Component
public class TurnObservationRegistry {

    /** turnId -> 观测上下文，模型 hook、工具 interceptor 和 summary emitter 都会按 turnId 共享它。 */
    private final ConcurrentHashMap<String, TurnObservationContext> contexts = new ConcurrentHashMap<>();

    public TurnObservationContext start(String threadId, String turnId, String providerId, String model) {
        return start(threadId, turnId, providerId, model, BusinessIdentityScope.UNSCOPED);
    }

    public TurnObservationContext start(String threadId, String turnId, String providerId, String model,
                                        BusinessIdentityScope businessIdentityScope) {
        return resolve(threadId, turnId, providerId, model, businessIdentityScope);
    }

    public TurnObservationContext getOrStart(String threadId, String turnId, String providerId, String model) {
        return getOrStart(threadId, turnId, providerId, model, BusinessIdentityScope.UNSCOPED);
    }

    public TurnObservationContext getOrStart(String threadId, String turnId, String providerId, String model,
                                             BusinessIdentityScope businessIdentityScope) {
        return resolve(threadId, turnId, providerId, model, businessIdentityScope);
    }

    private TurnObservationContext resolve(String threadId, String turnId, String providerId, String model,
                                           BusinessIdentityScope businessIdentityScope) {
        BusinessIdentityScope requiredScope = businessIdentityScope == null
                ? BusinessIdentityScope.UNSCOPED
                : businessIdentityScope;
        return contexts.compute(turnId, (ignored, existing) -> {
            if (existing == null) {
                return TurnObservationContext.start(threadId, turnId, providerId, model, requiredScope);
            }
            if (!existing.threadId().equals(threadId)
                    || !existing.businessIdentityScope().equals(requiredScope)) {
                throw new IllegalStateException("turn observation scope does not match the existing context");
            }
            // start 也是幂等取得：重复入口必须保留已经累计的 token、工具次数和计时起点。
            return existing;
        });
    }

    public void remove(String turnId) {
        contexts.remove(turnId);
    }
}
