package com.wzx.babiq.server.application;

import com.wzx.babiq.server.agent.AgentLoop;
import com.wzx.babiq.server.agent.PendingApprovals;
import com.wzx.babiq.server.application.action.ApplicationOutboundRequestTracker;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedBusinessIdentity;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.ConversationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ApplicationBridgeLifecycleCoordinatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void connectionCloseWaitsForActiveScopedReaderBeforeClearingIdentity() throws Exception {
        BusinessDesktopModeProperties properties = mock(BusinessDesktopModeProperties.class);
        when(properties.enabled()).thenReturn(true);
        when(properties.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(properties);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ObjectProvider<BusinessDesktopConnectionRegistry> connectionProvider = mock(ObjectProvider.class);
        ObjectProvider<ApplicationIdentityRegistry> identityProvider = mock(ObjectProvider.class);
        when(connectionProvider.getIfAvailable()).thenReturn(connections);
        when(identityProvider.getIfAvailable()).thenReturn(identities);
        BusinessIdentityScopeService scopes = new BusinessIdentityScopeService(
                properties, connectionProvider, identityProvider);
        String reservationId = connections.reserve("desktop", "desktop-session");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservationId, "desktop", "desktop-session", "ws");
        TrustedBusinessIdentity identity = identities.bind(connection, identityMessage());
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 1, "user", "tenant", "platform");
        CountDownLatch releaseEntered = new CountDownLatch(1);
        connections.addCloseListener((released, reason) -> releaseEntered.countDown());
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), connections, identities,
                mock(ApplicationCatalogRegistry.class), mock(ApplicationPageContextRegistry.class),
                mock(PendingApplicationActions.class), mock(ConversationService.class),
                mock(PendingApprovals.class), mock(AgentLoop.class));
        coordinator.registerListeners();
        CountDownLatch readerEntered = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        ExecutorService releaseExecutor = Executors.newSingleThreadExecutor();

        try {
            CompletableFuture<Optional<Boolean>> read = CompletableFuture.supplyAsync(() ->
                    scopes.withActiveConnectionScope(scope, active -> {
                        readerEntered.countDown();
                        try {
                            if (!releaseReader.await(3, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("test reader interrupted", interrupted);
                        }
                        assertThat(identities.current(connection)).contains(identity);
                        return true;
                    }));
            assertThat(readerEntered.await(3, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> release = CompletableFuture.supplyAsync(() ->
                    connections.release(reservationId, connection.webSocketSessionId()), releaseExecutor);
            assertThat(releaseEntered.await(3, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> release.get(200, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(identities.current(connection)).contains(identity);
            } finally {
                releaseReader.countDown();
            }

            assertThat(read.get(3, TimeUnit.SECONDS)).contains(true);
            assertThat(release.get(3, TimeUnit.SECONDS)).isTrue();
            assertThat(identities.current(connection)).isEmpty();
        } finally {
            releaseReader.countDown();
            releaseExecutor.shutdownNow();
            assertThat(releaseExecutor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void identityChangeClearsApprovalAndPausedAgentOncePerAffectedThread() {
        ConversationService conversations = mock(ConversationService.class);
        PendingApprovals approvals = mock(PendingApprovals.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        PendingApplicationActions actions = mock(PendingApplicationActions.class);
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), mock(BusinessDesktopConnectionRegistry.class),
                mock(ApplicationIdentityRegistry.class), mock(ApplicationCatalogRegistry.class),
                mock(ApplicationPageContextRegistry.class), actions, conversations, approvals, agentLoop);
        TrustedBusinessIdentity oldIdentity = identity("auth-old", 3, "user-old", "tenant-old");
        TrustedBusinessIdentity newIdentity = identity("auth-new", 4, "user-new", "tenant-new");
        BusinessIdentityScope oldScope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth-old", 3, "user-old", "tenant-old", "platform");
        when(conversations.expirePreExecutionTurns(oldScope, "business identity changed"))
                .thenReturn(List.of("thread-a", "thread-b"));

        coordinator.onIdentityChanged(
                new TrustedDesktopConnection("reservation", "desktop", "desktop-session", "ws"),
                oldIdentity, newIdentity);

        verify(approvals).remove("thread-a");
        verify(approvals).remove("thread-b");
        verify(agentLoop).forgetPaused("thread-a");
        verify(agentLoop).forgetPaused("thread-b");
        verify(actions).expirePreExecution(oldIdentityContext(oldIdentity), "business identity changed");
    }

    @Test
    void identityCleanupContinuesAfterIndividualStepFailures() {
        ConversationService conversations = mock(ConversationService.class);
        PendingApprovals approvals = mock(PendingApprovals.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        PendingApplicationActions actions = mock(PendingApplicationActions.class);
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), mock(BusinessDesktopConnectionRegistry.class),
                mock(ApplicationIdentityRegistry.class), mock(ApplicationCatalogRegistry.class),
                mock(ApplicationPageContextRegistry.class), actions, conversations, approvals, agentLoop);
        TrustedBusinessIdentity oldIdentity = identity("auth-old", 3, "user-old", "tenant-old");
        when(conversations.expirePreExecutionTurns(
                BusinessIdentityScope.scoped(
                        "desktop", "desktop-session", "auth-old", 3,
                        "user-old", "tenant-old", "platform"),
                "business identity changed"))
                .thenReturn(List.of("thread-a", "thread-b"));
        doThrow(new IllegalStateException("approval cleanup failed"))
                .when(approvals).remove("thread-a");
        doThrow(new IllegalStateException("paused cleanup failed"))
                .when(agentLoop).forgetPaused("thread-b");

        assertThatCode(() -> coordinator.onIdentityChanged(
                new TrustedDesktopConnection("reservation", "desktop", "desktop-session", "ws"),
                oldIdentity, identity("auth-new", 4, "user-new", "tenant-new")))
                .doesNotThrowAnyException();

        verify(agentLoop).forgetPaused("thread-a");
        verify(approvals).remove("thread-b");
        verify(actions).expirePreExecution(oldIdentityContext(oldIdentity), "business identity changed");
    }

    @Test
    void actionCleanupStillRunsWhenTurnCleanupFails() {
        ConversationService conversations = mock(ConversationService.class);
        PendingApplicationActions actions = mock(PendingApplicationActions.class);
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), mock(BusinessDesktopConnectionRegistry.class),
                mock(ApplicationIdentityRegistry.class), mock(ApplicationCatalogRegistry.class),
                mock(ApplicationPageContextRegistry.class), actions, conversations,
                mock(PendingApprovals.class), mock(AgentLoop.class));
        TrustedBusinessIdentity oldIdentity = identity("auth-old", 3, "user-old", "tenant-old");
        when(conversations.expirePreExecutionTurns(
                BusinessIdentityScope.scoped(
                        "desktop", "desktop-session", "auth-old", 3,
                        "user-old", "tenant-old", "platform"),
                "business identity changed"))
                .thenThrow(new IllegalStateException("turn cleanup failed"));

        assertThatCode(() -> coordinator.onIdentityChanged(
                new TrustedDesktopConnection("reservation", "desktop", "desktop-session", "ws"),
                oldIdentity, identity("auth-new", 4, "user-new", "tenant-new")))
                .doesNotThrowAnyException();

        verify(actions).expirePreExecution(oldIdentityContext(oldIdentity), "business identity changed");
    }

    @Test
    void connectionCloseContinuesAfterEveryIndividualCleanupFailure() {
        for (int failingStep = 0; failingStep < 5; failingStep++) {
            ApplicationOutboundRequestTracker outbound = mock(ApplicationOutboundRequestTracker.class);
            ApplicationPageContextRegistry contexts = mock(ApplicationPageContextRegistry.class);
            ApplicationCatalogRegistry catalogs = mock(ApplicationCatalogRegistry.class);
            ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
            PendingApplicationActions actions = mock(PendingApplicationActions.class);
            TrustedDesktopConnection connection = new TrustedDesktopConnection(
                    "reservation", "desktop", "desktop-session", "ws");
            switch (failingStep) {
                case 0 -> doThrow(new IllegalStateException("outbound failed"))
                        .when(outbound).closePending(org.mockito.ArgumentMatchers.eq("ws"),
                                org.mockito.ArgumentMatchers.any());
                case 1 -> doThrow(new IllegalStateException("context failed")).when(contexts).clear(connection);
                case 2 -> doThrow(new IllegalStateException("catalog failed")).when(catalogs).clear(connection);
                case 3 -> doThrow(new IllegalStateException("identity failed")).when(identities).clear(connection);
                case 4 -> doThrow(new IllegalStateException("pending failed"))
                        .when(actions).onConnectionClosed("ws", "closed");
                default -> throw new IllegalStateException("unexpected step");
            }
            ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                    outbound, mock(BusinessDesktopConnectionRegistry.class), identities, catalogs, contexts,
                    actions, mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class));

            assertThatCode(() -> coordinator.onConnectionClosed(connection, "closed"))
                    .as("failingStep=" + failingStep)
                    .doesNotThrowAnyException();

            verify(outbound).closePending(org.mockito.ArgumentMatchers.eq("ws"),
                    org.mockito.ArgumentMatchers.any());
            verify(contexts).clear(connection);
            verify(catalogs).clear(connection);
            verify(identities).clear(connection);
            verify(actions).onConnectionClosed("ws", "closed");
        }
    }

    @Test
    void cleanupLogsContainOnlyFixedStepAndFailureType(CapturedOutput output) {
        String secret = "secret-tenant-SQL-E:/private/case.db";
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.expirePreExecutionTurns(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException(secret));
        ApplicationOutboundRequestTracker outbound = mock(ApplicationOutboundRequestTracker.class);
        doThrow(new IllegalStateException(secret)).when(outbound).closePending(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                outbound, mock(BusinessDesktopConnectionRegistry.class), mock(ApplicationIdentityRegistry.class),
                mock(ApplicationCatalogRegistry.class), mock(ApplicationPageContextRegistry.class),
                mock(PendingApplicationActions.class), conversations,
                mock(PendingApprovals.class), mock(AgentLoop.class));
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation", "desktop", "desktop-session", "ws");

        coordinator.onConnectionClosed(connection, "closed");
        coordinator.onIdentityChanged(connection,
                identity("auth-old", 3, "user-old", "tenant-old"),
                identity("auth-new", 4, "user-new", "tenant-new"));

        assertThat(output).contains("IllegalStateException");
        assertThat(output).doesNotContain(secret, "secret-tenant", "private/case.db");
    }

    private static TrustedBusinessIdentity identity(
            String authSessionId, long epoch, String userId, String tenantId) {
        return new TrustedBusinessIdentity(
                "reservation", "ws", "desktop", "desktop-session", authSessionId, epoch,
                userId, tenantId, "platform", Set.of("lawyer"), Set.of("case:read"));
    }

    private static ApplicationIdentityMessage identityMessage() {
        return new ApplicationIdentityMessage(
                "1.0", "desktop", "desktop-session", "auth", 1, 1,
                "2026-07-18T00:00:00Z", "user", "tenant", "platform",
                true, Set.of("lawyer"), Set.of("case:read"));
    }

    private static com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext
            oldIdentityContext(TrustedBusinessIdentity identity) {
        return new com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext(
                identity.reservationId(), identity.webSocketSessionId(), identity.desktopInstanceId(),
                identity.desktopSessionId(), identity.authSessionId(), identity.identityEpoch(),
                identity.userId(), identity.tenantId(), identity.platformId());
    }
}
