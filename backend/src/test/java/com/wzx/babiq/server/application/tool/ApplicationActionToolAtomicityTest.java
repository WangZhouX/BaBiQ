package com.wzx.babiq.server.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.action.PendingApplicationAction;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.application.protocol.ApplicationCatalogMessage;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationActionToolAtomicityTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final TrustedDesktopConnection connection = new TrustedDesktopConnection(
            "reservation-1", "desktop-1", "desktop-session-1", "websocket-1");
    private final BusinessIdentityScope scopeA = BusinessIdentityScope.scoped(
            "desktop-1", "desktop-session-1", "auth-a", 1,
            "user-a", "tenant-a", "platform-1");

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void frozen_a_scope_fails_closed_when_b_is_installed_before_atomic_section() throws Exception {
        ApplicationIdentityRegistry identities = identitiesWithA();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        publish(catalogs, contexts, "auth-a", 1, "user-a", "tenant-a", "a.action", "A page");
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        CountDownLatch located = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(connections.findByDesktopSessionId("desktop-session-1")).thenAnswer(ignored -> {
            located.countDown();
            assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(connection);
        });
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        ApplicationActionProtocolHandler protocol = mock(ApplicationActionProtocolHandler.class);
        ApplicationActionTool tool = tool(scopeService(connections, identities), catalogs, contexts, pending, protocol);

        Future<ApplicationActionToolResult> call = executor.submit(() -> invoke(tool, "a.action"));
        assertThat(located.await(5, TimeUnit.SECONDS)).isTrue();
        switchToB(identities, catalogs, contexts);
        resume.countDown();

        assertThat(call.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo("auth_expired");
        verify(pending, never()).register(any(), any(), any(), any(), any());
        verify(protocol, never()).sendActionRequest(any(), any());
    }

    @Test
    void identity_switch_waits_until_catalog_page_validation_and_pending_registration_finish() throws Exception {
        ApplicationIdentityRegistry identities = identitiesWithA();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        PausingPageContextRegistry contexts = new PausingPageContextRegistry(identities, catalogs);
        publish(catalogs, contexts, "auth-a", 1, "user-a", "tenant-a", "a.action", "A page");
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session-1")).thenReturn(Optional.of(connection));
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        when(pending.register(eq("execution-fixed"), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(terminalA()));
        ApplicationActionProtocolHandler protocol = mock(ApplicationActionProtocolHandler.class);
        when(protocol.sendActionRequest(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        ApplicationActionTool tool = tool(scopeService(connections, identities), catalogs, contexts, pending, protocol);
        contexts.arm();

        Future<ApplicationActionToolResult> call = executor.submit(() -> invoke(tool, "a.action"));
        assertThat(contexts.beforeRead.await(5, TimeUnit.SECONDS)).isTrue();
        Future<?> switchToB = executor.submit(() -> switchToB(identities, catalogs, contexts));
        try {
            switchToB.get(300, TimeUnit.MILLISECONDS);
            throw new AssertionError("identity switch must wait for the connection atomic section");
        } catch (TimeoutException expected) {
            assertThat(pendingRegisterCount(pending)).isZero();
        } finally {
            contexts.resume.countDown();
        }

        ApplicationActionToolResult result = call.get(5, TimeUnit.SECONDS);
        switchToB.get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo("completed");
        verify(pending).register(eq("execution-fixed"), any(), any(), any(), any());
        var payload = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(protocol).sendActionRequest(any(), payload.capture());
        assertThat(payload.getValue().path("actionId").asText()).isEqualTo("a.action");
        assertThat(payload.getValue().toString()).doesNotContain("b.action", "B page");
    }

    private int pendingRegisterCount(PendingApplicationActions pending) {
        return org.mockito.Mockito.mockingDetails(pending).getInvocations().stream()
                .map(invocation -> invocation.getMethod().getName())
                .mapToInt(name -> "register".equals(name) ? 1 : 0)
                .sum();
    }

    private ApplicationActionTool tool(
            BusinessIdentityScopeService scopes,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            PendingApplicationActions pending,
            ApplicationActionProtocolHandler protocol) {
        return new ApplicationActionTool(
                json, scopes, catalogs, contexts, pending, protocol,
                () -> "execution-fixed", () -> 0L);
    }

    private ApplicationActionToolResult invoke(ApplicationActionTool tool, String actionId) {
        try (ApplicationToolInvocationContext.Scope ignored = ApplicationToolInvocationContext.install(
                new ApplicationToolInvocationContext.Invocation("tool-call-a", "thread-a", "turn-a", scopeA))) {
            return tool.applicationAction(
                    actionId, 1, json.createObjectNode(), "page-a", 1L, new ToolContext(Map.of()));
        }
    }

    private BusinessIdentityScopeService scopeService(
            BusinessDesktopConnectionRegistry connections,
            ApplicationIdentityRegistry identities) {
        BusinessDesktopModeProperties properties = mock(BusinessDesktopModeProperties.class);
        when(properties.enabled()).thenReturn(true);
        ObjectProvider<BusinessDesktopConnectionRegistry> connectionsProvider = mock(ObjectProvider.class);
        ObjectProvider<ApplicationIdentityRegistry> identitiesProvider = mock(ObjectProvider.class);
        when(connectionsProvider.getIfAvailable()).thenReturn(connections);
        when(identitiesProvider.getIfAvailable()).thenReturn(identities);
        return new BusinessIdentityScopeService(properties, connectionsProvider, identitiesProvider);
    }

    private ApplicationIdentityRegistry identitiesWithA() {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        identities.bind(connection, identity("auth-a", 1, "user-a", "tenant-a"));
        return identities;
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
            publish(catalogs, contexts, "auth-b", 2, "user-b", "tenant-b", "b.action", "B page");
        }
    }

    private void publish(
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            String authSessionId,
            long identityEpoch,
            String userId,
            String tenantId,
            String actionId,
            String pageTitle) {
        ObjectNode catalog = json.createObjectNode();
        ObjectNode action = catalog.putObject("actions").putObject(actionId);
        action.put("id", actionId).put("version", 1).put("enabled", true)
                .put("title", actionId).put("description", "action").put("risk", "read_only");
        action.putArray("requiredPermissions").add("case:read");
        catalogs.register(connection, message(
                authSessionId, identityEpoch, userId, tenantId, 1, 1, catalog));
        ObjectNode page = json.createObjectNode().put("pageId", "page-a")
                .put("pageTitle", pageTitle).put("contextRevision", 1);
        contexts.publish(connection, message(
                authSessionId, identityEpoch, userId, tenantId, 1, 1, page));
    }

    private ApplicationIdentityMessage identity(
            String authSessionId, long epoch, String userId, String tenantId) {
        return new ApplicationIdentityMessage(
                "1.0", "desktop-1", "desktop-session-1", authSessionId, epoch, epoch,
                "2026-07-17T00:00:00Z", userId, tenantId, "platform-1", true,
                Set.of("lawyer"), Set.of("case:read"));
    }

    private ApplicationCatalogMessage message(
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

    private PendingApplicationAction terminalA() {
        return new PendingApplicationAction(
                "execution-fixed", new PendingApplicationAction.Correlation("thread-a", "turn-a", "tool-call-a"),
                PendingApplicationAction.Path.READ_ONLY, PendingApplicationAction.State.COMPLETED,
                null, null, Instant.EPOCH,
                new PendingApplicationAction.ConnectionContext(
                        "reservation-1", "websocket-1", "desktop-1", "desktop-session-1",
                        "auth-a", 1, "user-a", "tenant-a", "platform-1"));
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
                        throw new IllegalStateException("page read barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("page read interrupted", interrupted);
                }
            }
            return super.current(connection);
        }
    }
}
