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
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.oa.session.BusinessOaAttachHandleRegistry;
import com.wzx.babiq.server.business.oa.session.DurableOaSessionFixture;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.OaSessionPhase;
import com.wzx.babiq.server.business.oa.session.OaSessionRecord;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.upload.BusinessBinaryLeaseLifecycle;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    void connectionCloseDetachesOaSessionBeforeIdentityCleanup() {
        ApplicationOutboundRequestTracker outbound = mock(ApplicationOutboundRequestTracker.class);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessOaSessionRegistry oaSessions = mock(BusinessOaSessionRegistry.class);
        BusinessOaAttachHandleRegistry attachHandles = mock(BusinessOaAttachHandleRegistry.class);
        BusinessBinaryLeaseLifecycle binaryLifecycle = mock(BusinessBinaryLeaseLifecycle.class);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation", "desktop", "desktop-session", "ws");
        TrustedBusinessIdentity identity = identity("auth", 3, "user", "tenant");
        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth", "desktop", "desktop-session", "ws", "user", "tenant", "platform",
                3, "credential-ref", 1, Instant.now());
        when(identities.current(connection)).thenReturn(Optional.of(identity));
        when(oaSessions.currentReady(connection, identity)).thenReturn(Optional.of(lease));
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                outbound, connections, identities, mock(ApplicationCatalogRegistry.class),
                mock(ApplicationPageContextRegistry.class), mock(PendingApplicationActions.class),
                mock(ConversationService.class), mock(PendingApprovals.class), mock(AgentLoop.class),
                oaSessions, attachHandles, binaryLifecycle);

        coordinator.onConnectionClosed(connection, "closed");

        org.mockito.InOrder cleanup =
                org.mockito.Mockito.inOrder(attachHandles, oaSessions, binaryLifecycle, identities);
        cleanup.verify(attachHandles).revoke(connection);
        cleanup.verify(oaSessions).detachBeforeCredentialCleanup(connection);
        cleanup.verify(binaryLifecycle).revoke(connection, lease);
        cleanup.verify(identities).clear(connection);
        verify(oaSessions).drainPendingCredentialCleanup();
    }

    @Test
    void connectionCloseDrainsDetachedCredentialsOutsideConnectionMonitor() {
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation", "desktop", "desktop-session", "ws");
        List<String> events = new ArrayList<>();
        LockObservingSecretStore secretStore = new LockObservingSecretStore(connection, events);
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secretStore);
        String activeRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        String stagedRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth", 2, "new-access".toCharArray(), "new-refresh".toCharArray());
        LifecycleOaSessionRepository repository = new LifecycleOaSessionRepository(events);
        repository.put(installingSession(activeRef, stagedRef));
        BusinessOaSessionRegistry oaSessions = DurableOaSessionFixture
                .memory(repository, credentials)
                .sessions();
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(repository.record().phase()).isEqualTo(OaSessionPhase.DETACHED);
            events.add("identity-clear");
            return null;
        }).when(identities).clear(connection);
        ApplicationBridgeLifecycleCoordinator coordinator = new ApplicationBridgeLifecycleCoordinator(
                mock(ApplicationOutboundRequestTracker.class), mock(BusinessDesktopConnectionRegistry.class),
                identities, mock(ApplicationCatalogRegistry.class), mock(ApplicationPageContextRegistry.class),
                mock(PendingApplicationActions.class), mock(ConversationService.class),
                mock(PendingApprovals.class), mock(AgentLoop.class), oaSessions, null);

        coordinator.onConnectionClosed(connection, "closed");

        assertThat(events.indexOf("session-detached"))
                .isLessThan(events.indexOf("identity-clear"));
        assertThat(secretStore.deleteCalls()).isEqualTo(1);
        assertThat(secretStore.connectionMonitorHeldDuringDelete()).isFalse();
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

    private static OaSessionRecord installingSession(String activeRef, String stagedRef) {
        Instant now = Instant.parse("2026-07-28T06:00:00Z");
        return new OaSessionRecord(
                "auth", "desktop", "desktop-session", "user", "tenant", "platform",
                OaSessionPhase.INSTALLING, 3, activeRef, stagedRef, 2,
                now, now, null, null, now,
                "installation", "desktop", "desktop-session", 3, now.plusSeconds(90));
    }

    private static final class LifecycleOaSessionRepository implements OaSessionRepository {
        private final List<String> events;
        private OaSessionRecord record;

        private LifecycleOaSessionRepository(List<String> events) {
            this.events = events;
        }

        private void put(OaSessionRecord value) {
            record = value;
        }

        private OaSessionRecord record() {
            return record;
        }

        @Override
        public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId)
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public Optional<OaSessionRecord> findByDesktopSession(
                String desktopInstanceId,
                String desktopSessionId) {
            return record != null
                    && record.desktopInstanceId().equals(desktopInstanceId)
                    && record.desktopSessionId().equals(desktopSessionId)
                    ? Optional.of(record) : Optional.empty();
        }

        @Override
        public OaSessionRecord insert(OaSessionRecord value) {
            record = value;
            return value;
        }

        @Override
        public OaSessionRecord update(OaSessionRecord value) {
            record = value;
            return value;
        }

        @Override
        public boolean compareAndSwapGeneration(
                String authSessionId,
                long expectedGeneration,
                OaSessionRecord next) {
            if (record == null || !record.authSessionId().equals(authSessionId)
                    || record.generation() != expectedGeneration) {
                return false;
            }
            record = next;
            recordTransition(next);
            return true;
        }

        @Override
        public boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) {
            if (!expected.equals(record)) {
                return false;
            }
            record = next;
            recordTransition(next);
            return true;
        }

        @Override
        public List<OaSessionRecord> listRecoverable() {
            return record == null ? List.of() : List.of(record);
        }

        private void recordTransition(OaSessionRecord next) {
            if (next.phase() == OaSessionPhase.DETACHED) {
                events.add("session-detached");
            }
        }
    }

    private static final class LockObservingSecretStore implements SecretStore {
        private final TrustedDesktopConnection connection;
        private final List<String> events;
        private final Map<String, String> values = new HashMap<>();
        private int sequence;
        private int deleteCalls;
        private boolean connectionMonitorHeldDuringDelete;

        private LockObservingSecretStore(
                TrustedDesktopConnection connection,
                List<String> events) {
            this.connection = connection;
            this.events = events;
        }

        @Override
        public String save(String namespace, String secretPlainText) {
            String secretRef = allocateRef(namespace);
            saveCharsAtRef(secretRef, secretPlainText.toCharArray());
            return secretRef;
        }

        @Override
        public String saveChars(String namespace, char[] secretChars) {
            return save(namespace, new String(secretChars));
        }

        @Override
        public String allocateRef(String namespace) {
            return "keystore://" + namespace + "/" + ++sequence;
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            values.put(secretRef, new String(secretChars));
        }

        @Override
        public List<String> listRefs(String namespacePrefix) {
            return values.keySet().stream()
                    .filter(ref -> ref.startsWith("keystore://" + namespacePrefix))
                    .sorted()
                    .toList();
        }

        @Override
        public Optional<String> load(String secretRef) {
            return Optional.ofNullable(values.get(secretRef));
        }

        @Override
        public void delete(String secretRef) {
            deleteCalls++;
            connectionMonitorHeldDuringDelete |= Thread.holdsLock(connection);
            events.add("secret-delete");
            values.remove(secretRef);
        }

        private int deleteCalls() {
            return deleteCalls;
        }

        private boolean connectionMonitorHeldDuringDelete() {
            return connectionMonitorHeldDuringDelete;
        }
    }

    private static com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext
            oldIdentityContext(TrustedBusinessIdentity identity) {
        return new com.wzx.babiq.server.application.action.PendingApplicationAction.ConnectionContext(
                identity.reservationId(), identity.webSocketSessionId(), identity.desktopInstanceId(),
                identity.desktopSessionId(), identity.authSessionId(), identity.identityEpoch(),
                identity.userId(), identity.tenantId(), identity.platformId());
    }
}
