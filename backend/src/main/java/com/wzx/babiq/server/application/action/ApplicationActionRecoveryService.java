package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.persistence.entity.ApplicationActionEntity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 启动时只收口遗留桌面动作，不恢复或续跑旧模型 Turn。 */
@Service
public class ApplicationActionRecoveryService {

    private static final List<String> PRE_EXECUTION =
            List.of("REQUESTED", "ACCEPTED", "PREVIEWED", "APPROVAL_REQUIRED");
    private final SQLiteApplicationActionTerminalStore store;
    private final Clock clock;

    @Autowired
    public ApplicationActionRecoveryService(SQLiteApplicationActionTerminalStore store) {
        this(store, Clock.systemUTC());
    }

    ApplicationActionRecoveryService(SQLiteApplicationActionTerminalStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public RecoveryReport recoverAbandonedActions() {
        int expired = 0;
        int unknown = 0;
        Instant now = clock.instant();
        for (ApplicationActionEntity entity : store.findByStatuses(PRE_EXECUTION)) {
            store.recover(entity, "recovery_expired", PendingApplicationAction.State.EXPIRED,
                    "server restarted before desktop execution", now);
            expired++;
        }
        for (ApplicationActionEntity entity : store.findByStatuses(List.of("EXECUTING"))) {
            store.recover(entity, "recovery_orphaned", PendingApplicationAction.State.OUTCOME_UNKNOWN,
                    "server restarted after desktop execution may have started", now);
            unknown++;
        }
        return new RecoveryReport(expired, unknown, now);
    }

    public record RecoveryReport(int expiredPreExecution, int outcomeUnknownExecuting, Instant recoveredAt) {
    }
}
