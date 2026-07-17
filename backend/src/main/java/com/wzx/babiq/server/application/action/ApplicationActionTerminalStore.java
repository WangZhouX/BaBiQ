package com.wzx.babiq.server.application.action;

import java.util.Optional;

/** 动作终态持久化端口；Task 28 提供 SQLite 实现。 */
public interface ApplicationActionTerminalStore {

    Optional<PendingApplicationAction> findTerminal(
            String executionId,
            PendingApplicationAction.Correlation correlation);

    void recordTerminal(PendingApplicationAction terminal, boolean lateResult);

    void queueReconciliation(PendingApplicationAction terminal);
}
