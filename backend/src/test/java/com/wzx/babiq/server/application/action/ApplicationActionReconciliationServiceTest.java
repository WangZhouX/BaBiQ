package com.wzx.babiq.server.application.action;

import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.protocol.ApplicationProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ApplicationActionReconciliationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void providerStoreQueryAndNullFutureFailuresAreBestEffortAndRedacted(CapturedOutput output) {
        String secret = "secret-tenant-SQL-E:/private/case.db";
        SQLiteApplicationActionTerminalStore providerStore = mock(SQLiteApplicationActionTerminalStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApplicationActionProtocolHandler> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenThrow(new IllegalStateException(secret));
        ApplicationActionReconciliationService providerFailure =
                new ApplicationActionReconciliationService(providerStore, provider);

        SQLiteApplicationActionTerminalStore storeFailureStore = mock(SQLiteApplicationActionTerminalStore.class);
        when(storeFailureStore.findOutcomeUnknownForDesktopIdentity(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(secret));
        ApplicationActionReconciliationService storeFailure = new ApplicationActionReconciliationService(
                storeFailureStore, action -> CompletableFuture.completedFuture(null), CLOCK);

        PendingApplicationAction action = action();
        SQLiteApplicationActionTerminalStore queryFailureStore = storeReturning(action);
        PendingApplicationActions.StatusQuery throwingQuery = ignored -> {
            throw new IllegalStateException(secret);
        };
        ApplicationActionReconciliationService queryFailure =
                new ApplicationActionReconciliationService(queryFailureStore, throwingQuery, CLOCK);
        ApplicationActionReconciliationService nullFuture =
                new ApplicationActionReconciliationService(storeReturning(action), ignored -> null, CLOCK);

        assertThatCode(() -> providerFailure.reconcile(connection(), identity())).doesNotThrowAnyException();
        assertThatCode(() -> storeFailure.reconcile(connection(), identity())).doesNotThrowAnyException();
        assertThatCode(() -> queryFailure.reconcile(connection(), identity())).doesNotThrowAnyException();
        assertThatCode(() -> nullFuture.reconcile(connection(), identity())).doesNotThrowAnyException();

        assertThat(output).contains("IllegalStateException");
        assertThat(output).doesNotContain(secret, "secret-tenant", "private/case.db");
    }

    @Test
    void lateAuditFailureIsBestEffortAndRedacted(CapturedOutput output) {
        String secret = "secret-tenant-SQL-E:/private/case.db";
        PendingApplicationAction action = action();
        SQLiteApplicationActionTerminalStore store = storeReturning(action);
        doThrow(new IllegalStateException(secret)).when(store).recordTerminal(any(),
                org.mockito.ArgumentMatchers.eq(true));
        PendingApplicationActions.StatusQuery query = ignored -> CompletableFuture.completedFuture(
                new PendingApplicationActions.RemoteStatus(
                        PendingApplicationAction.State.COMPLETED,
                        ApplicationProtocol.objectNode().put("ok", true)));
        ApplicationActionReconciliationService service =
                new ApplicationActionReconciliationService(store, query, CLOCK);

        assertThatCode(() -> service.reconcile(connection(), identity())).doesNotThrowAnyException();

        assertThat(output).contains("IllegalStateException");
        assertThat(output).doesNotContain(secret, "secret-tenant", "private/case.db");
    }

    @Test
    void exceptionalStatusFutureIsBestEffortAndRedacted(CapturedOutput output) {
        String secret = "secret-tenant-SQL-E:/private/case.db";
        CompletableFuture<PendingApplicationActions.RemoteStatus> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException(secret));
        ApplicationActionReconciliationService service = new ApplicationActionReconciliationService(
                storeReturning(action()), ignored -> failed, CLOCK);

        assertThatCode(() -> service.reconcile(connection(), identity())).doesNotThrowAnyException();

        assertThat(output).contains("IllegalStateException");
        assertThat(output).doesNotContain(secret, "secret-tenant", "private/case.db");
    }

    private SQLiteApplicationActionTerminalStore storeReturning(PendingApplicationAction action) {
        SQLiteApplicationActionTerminalStore store = mock(SQLiteApplicationActionTerminalStore.class);
        when(store.findOutcomeUnknownForDesktopIdentity(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(action));
        return store;
    }

    private PendingApplicationAction action() {
        return new PendingApplicationAction(
                "execution-1",
                new PendingApplicationAction.Correlation("thread-1", "turn-1", "tool-1"),
                PendingApplicationAction.Path.REVERSIBLE_WRITE,
                PendingApplicationAction.State.OUTCOME_UNKNOWN,
                null,
                "unknown",
                CLOCK.instant(),
                new PendingApplicationAction.ConnectionContext(
                        "reservation", "ws", "desktop", "desktop-session", "auth", 3,
                        "user", "tenant", "platform"));
    }

    private TrustedDesktopConnection connection() {
        return new TrustedDesktopConnection("reservation", "desktop", "desktop-session", "ws");
    }

    private TrustedBusinessIdentity identity() {
        return new TrustedBusinessIdentity(
                "reservation", "ws", "desktop", "desktop-session", "auth", 3,
                "user", "tenant", "platform", Set.of("lawyer"), Set.of("case:read"));
    }
}
