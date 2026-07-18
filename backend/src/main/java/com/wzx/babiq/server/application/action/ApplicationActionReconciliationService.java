package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "babiq.business", name = "enabled", havingValue = "true")
public final class ApplicationActionReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationActionReconciliationService.class);

    private final SQLiteApplicationActionTerminalStore store;
    /** 仅在真正重连对账时解析协议适配器，避免身份 handler 与动作 handler 形成构造环。 */
    private final Supplier<PendingApplicationActions.StatusQuery> statusQuerySupplier;
    private final Clock clock;

    @Autowired
    public ApplicationActionReconciliationService(
            SQLiteApplicationActionTerminalStore store,
            ObjectProvider<ApplicationActionProtocolHandler> statusQueryProvider) {
        this(store, () -> statusQueryProvider.getObject(), Clock.systemUTC());
    }

    ApplicationActionReconciliationService(
            SQLiteApplicationActionTerminalStore store,
            PendingApplicationActions.StatusQuery statusQuery,
            Clock clock) {
        this(store, () -> statusQuery, clock);
    }

    private ApplicationActionReconciliationService(
            SQLiteApplicationActionTerminalStore store,
            Supplier<PendingApplicationActions.StatusQuery> statusQuerySupplier,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.statusQuerySupplier = Objects.requireNonNull(statusQuerySupplier, "statusQuerySupplier");
        this.clock = clock;
    }

    public void reconcile(TrustedDesktopConnection connection, TrustedBusinessIdentity identity) {
        PendingApplicationActions.StatusQuery statusQuery;
        List<PendingApplicationAction> actions;
        try {
            statusQuery = statusQuerySupplier.get();
            if (statusQuery == null) {
                log.warn("Application action reconciliation skipped: step=status_query_provider, reasonType=NullProvider");
                return;
            }
            actions = store.findOutcomeUnknownForDesktopIdentity(
                    identity.desktopInstanceId(), identity.desktopSessionId(), identity.authSessionId(),
                    identity.identityEpoch(), identity.userId(), identity.tenantId(), identity.platformId(),
                    connection.reservationId(), connection.webSocketSessionId());
            if (actions == null) {
                log.warn("Application action reconciliation skipped: step=terminal_store, reasonType=NullResult");
                return;
            }
        } catch (RuntimeException failure) {
            log.warn("Application action reconciliation skipped: step=setup, reasonType={}",
                    failure.getClass().getSimpleName());
            return;
        }
        for (PendingApplicationAction action : actions) {
            CompletableFuture<PendingApplicationActions.RemoteStatus> query;
            try {
                query = statusQuery.query(action);
                if (query == null) {
                    log.warn("Application action reconciliation skipped: step=status_query, reasonType=NullFuture");
                    continue;
                }
            } catch (RuntimeException failure) {
                log.warn("Application action reconciliation skipped: step=status_query, reasonType={}",
                        failure.getClass().getSimpleName());
                continue;
            }
            query.whenComplete((remote, failure) -> {
                if (failure != null || remote == null || remote.terminal() == null) {
                    if (failure != null) {
                        log.warn("Application action reconciliation failed: step=status_response, reasonType={}",
                                failure.getClass().getSimpleName());
                    }
                    return;
                }
                PendingApplicationAction late = action.toTerminal(
                        remote.terminal(), remote.payload(), "reconciled desktop terminal", clock.instant());
                try {
                    store.recordTerminal(late, true);
                } catch (RuntimeException auditFailure) {
                    log.warn("Application action reconciliation failed: step=terminal_audit, reasonType={}",
                            auditFailure.getClass().getSimpleName());
                }
            });
        }
    }
}
