package com.wzx.babiq.server.application.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.context.ApplicationContextModelContributor;
import com.wzx.babiq.server.application.protocol.ApplicationCatalogMessage;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessApplicationContextAtomicityTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-1", "desktop-1", "desktop-session-1", "websocket-1");

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void frozen_a_scope_never_reads_b_snapshots_after_identity_switch() throws Exception {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        identities.bind(connection, identity("auth-a", 1, "user-a", "tenant-a"));
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        publish(catalogs, contexts, "auth-a", 1, "user-a", "tenant-a", "A_ACTION", "A_PAGE");
        BusinessIdentityScope frozenA = scope("auth-a", 1, "user-a", "tenant-a");
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        CountDownLatch connectionLocated = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(connections.findByDesktopSessionId("desktop-session-1")).thenAnswer(ignored -> {
            connectionLocated.countDown();
            assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(connection);
        });
        BusinessIdentityScopeService scopes = new BusinessIdentityScopeService(true, connections, identities);
        ApplicationContextModelContributor contributor =
                new ApplicationContextModelContributor(scopes, catalogs, contexts, json);

        Future<java.util.List<String>> contribution =
                executor.submit(() -> contributor.contribute(frozenA));
        assertThat(connectionLocated.await(5, TimeUnit.SECONDS)).isTrue();

        switchToB(identities, catalogs, contexts);
        resume.countDown();

        assertThat(contribution.get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void catalog_and_page_are_read_from_one_identity_atomic_section() throws Exception {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        identities.bind(connection, identity("auth-a", 1, "user-a", "tenant-a"));
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        PausingPageContextRegistry contexts = new PausingPageContextRegistry(identities, catalogs);
        publish(catalogs, contexts, "auth-a", 1, "user-a", "tenant-a", "A_ACTION", "A_PAGE");
        BusinessIdentityScope frozenA = scope("auth-a", 1, "user-a", "tenant-a");
        ApplicationContextModelContributor contributor = new ApplicationContextModelContributor(
                scopeService(identities), catalogs, contexts, json);
        contexts.arm();

        Future<java.util.List<String>> contribution =
                executor.submit(() -> contributor.contribute(frozenA));
        assertThat(contexts.beforeRead.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> switchToB = executor.submit(() -> switchToB(identities, catalogs, contexts));

        boolean switchedBeforeReadFinished;
        try {
            switchToB.get(300, TimeUnit.MILLISECONDS);
            switchedBeforeReadFinished = true;
        } catch (TimeoutException expectedWhenReadOwnsConnectionMonitor) {
            switchedBeforeReadFinished = false;
        } finally {
            contexts.resume.countDown();
        }

        java.util.List<String> facts = contribution.get(5, TimeUnit.SECONDS);
        switchToB.get(5, TimeUnit.SECONDS);
        assertThat(facts).noneMatch(fact -> fact.contains("B_ACTION") || fact.contains("B_PAGE"));
        if (switchedBeforeReadFinished) {
            assertThat(facts).isEmpty();
        }
    }

    private BusinessIdentityScopeService scopeService(ApplicationIdentityRegistry identities) {
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session-1")).thenReturn(Optional.of(connection));
        return new BusinessIdentityScopeService(true, connections, identities);
    }

    private void switchToB(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts) {
        identities.update(connection, identity("auth-b", 2, "user-b", "tenant-b"), () -> {
            catalogs.clear(connection);
            contexts.clear(connection);
        });
        synchronized (connection) {
            publish(catalogs, contexts, "auth-b", 2, "user-b", "tenant-b", "B_ACTION", "B_PAGE");
        }
    }

    private void publish(
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            String authSessionId,
            long identityEpoch,
            String userId,
            String tenantId,
            String actionTitle,
            String pageTitle) {
        ObjectNode catalog = json.createObjectNode();
        ObjectNode action = catalog.putObject("actions").putObject("case.read");
        action.put("id", "case.read").put("version", 1).put("enabled", true)
                .put("title", actionTitle).put("description", "read case").put("risk", "read_only");
        action.putArray("requiredPermissions").add("case:read");
        catalogs.register(connection, catalogMessage(
                authSessionId, identityEpoch, userId, tenantId, 1, 1, catalog));

        ObjectNode page = json.createObjectNode().put("pageId", "case-page")
                .put("pageTitle", pageTitle).put("contextRevision", 1);
        contexts.publish(connection, catalogMessage(
                authSessionId, identityEpoch, userId, tenantId, 1, 1, page));
    }

    private ApplicationIdentityMessage identity(
            String authSessionId, long epoch, String userId, String tenantId) {
        return new ApplicationIdentityMessage(
                "1.0", "desktop-1", "desktop-session-1", authSessionId, epoch, epoch,
                "2026-07-17T00:00:00Z", userId, tenantId, "platform-1", true,
                Set.of("lawyer"), Set.of("case:read"));
    }

    private ApplicationCatalogMessage catalogMessage(
            String authSessionId,
            long identityEpoch,
            String userId,
            String tenantId,
            long catalogEpoch,
            long contextSequence,
            JsonNode payload) {
        return new ApplicationCatalogMessage(
                "1.0", "desktop-1", "desktop-session-1", authSessionId, identityEpoch,
                contextSequence, "2026-07-17T00:00:00Z", userId, tenantId, "platform-1",
                catalogEpoch, contextSequence,
                payload.toString().getBytes(StandardCharsets.UTF_8).length, payload);
    }

    private BusinessIdentityScope scope(
            String authSessionId, long identityEpoch, String userId, String tenantId) {
        return BusinessIdentityScope.scoped(
                "desktop-1", "desktop-session-1", authSessionId, identityEpoch,
                userId, tenantId, "platform-1");
    }

    private static final class PausingPageContextRegistry extends ApplicationPageContextRegistry {
        private final CountDownLatch beforeRead = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private volatile boolean armed;

        private PausingPageContextRegistry(
                ApplicationIdentityRegistry identities,
                ApplicationCatalogRegistry catalogs) {
            super(identities, catalogs);
        }

        private void arm() {
            armed = true;
        }

        @Override
        public synchronized Optional<PageContextSnapshot> current(TrustedDesktopConnection connection) {
            if (armed) {
                beforeRead.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("page context read barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("page context read interrupted", interrupted);
                }
            }
            return super.current(connection);
        }
    }
}
