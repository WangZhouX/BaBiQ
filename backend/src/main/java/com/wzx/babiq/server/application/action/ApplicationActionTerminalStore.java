package com.wzx.babiq.server.application.action;

import java.util.Optional;

/** 动作终态持久化端口；Task 28 提供 SQLite 实现。 */
public interface ApplicationActionTerminalStore {

    default void recordRegistered(
            PendingApplicationAction requested,
            String actionId,
            int actionVersion,
            String requestFingerprint) {
    }

    default void recordRegistered(PendingApplicationAction requested) {
    }

    default void recordTransition(
            PendingApplicationAction previous,
            PendingApplicationAction current,
            boolean lateResult) {
    }

    Optional<PendingApplicationAction> findTerminal(
            String executionId,
            PendingApplicationAction.Correlation correlation);

    default Optional<PendingApplicationAction> findTerminal(
            String executionId,
            PendingApplicationAction.Correlation correlation,
            PendingApplicationAction.ConnectionContext scope) {
        return findTerminal(executionId, correlation)
                .filter(action -> scope == null || scope.equals(action.connectionContext()));
    }

    void recordTerminal(PendingApplicationAction terminal, boolean lateResult);

    void queueReconciliation(PendingApplicationAction terminal);
}
