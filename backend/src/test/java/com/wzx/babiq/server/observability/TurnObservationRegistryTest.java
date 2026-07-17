package com.wzx.babiq.server.observability;

import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证同一 turn 的观测上下文不能被其他 thread 或身份作用域覆盖。 */
class TurnObservationRegistryTest {

    private final BusinessIdentityScope tenantA = BusinessIdentityScope.scoped(
            "desktop", "session", "auth-a", 1, "user", "tenant-a", "platform");
    private final BusinessIdentityScope tenantB = BusinessIdentityScope.scoped(
            "desktop", "session", "auth-b", 2, "user", "tenant-b", "platform");

    @Test
    void repeatedStartWithTheSameThreadAndScopeIsIdempotentAndPreservesCounters() {
        TurnObservationRegistry registry = new TurnObservationRegistry();
        TurnObservationContext first = registry.start("thread-a", "turn-a", "provider", "model", tenantA);
        first.recordTokens(10, 5);

        TurnObservationContext repeated = registry.start("thread-a", "turn-a", "provider", "model", tenantA);

        assertThat(repeated).isSameAs(first);
        assertThat(repeated.totalTokens()).isEqualTo(15);
    }

    @Test
    void startAndGetOrStartRejectExistingTurnWithDifferentThreadOrScope() {
        TurnObservationRegistry registry = new TurnObservationRegistry();
        registry.start("thread-a", "turn-a", "provider", "model", tenantA);

        assertThatThrownBy(() -> registry.start("thread-b", "turn-a", "provider", "model", tenantA))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.getOrStart("thread-a", "turn-a", "provider", "model", tenantB))
                .isInstanceOf(IllegalStateException.class);
    }
}
