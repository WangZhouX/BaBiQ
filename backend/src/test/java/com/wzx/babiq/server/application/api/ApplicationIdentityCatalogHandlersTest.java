package com.wzx.babiq.server.application.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopHandshakeInterceptor;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.protocol.ApplicationCatalogMessage;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.application.protocol.ApplicationProtocol;
import com.wzx.babiq.server.application.action.ApplicationActionReconciliationService;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class ApplicationIdentityCatalogHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApplicationIdentityRegistry identities;
    private ApplicationCatalogRegistry catalogs;
    private ApplicationPageContextRegistry contexts;
    private BusinessDesktopConnectionRegistry connections;
    private ApplicationIdentityProtocolHandler identityHandler;
    private ApplicationCatalogProtocolHandler catalogHandler;
    private WebSocketSession session;

    @BeforeEach
    void setUp() {
        identities = new ApplicationIdentityRegistry();
        catalogs = new ApplicationCatalogRegistry(identities);
        contexts = new ApplicationPageContextRegistry(identities, catalogs);
        connections = mock(BusinessDesktopConnectionRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session-1"))
                .thenReturn(Optional.of(trustedConnectionMessage()));
        identityHandler = new ApplicationIdentityProtocolHandler(identities, catalogs, contexts, connections);
        catalogHandler = new ApplicationCatalogProtocolHandler(identities, catalogs, contexts, connections);
        session = trustedSession();
    }

    @Test
    void identityHandlerOwnsBindAndUpdateAndClearsBusinessSnapshotsOnChange() throws Exception {
        assertThat(identityHandler.methods()).containsExactlyInAnyOrder(
                "application/identity/bind", "application/identity/update");

        Object bindResult = identityHandler.handle(
                "application/identity/bind", node(identity(8, true)), session);

        assertThat(bindResult).isEqualTo(Map.of("authenticated", true, "identityEpoch", 8L));
        catalogs.register(trustedConnectionMessage(), catalogMessage(1, 1, catalogPayload()));
        contexts.publish(trustedConnectionMessage(), contextMessage(2, 1, contextPayload()));

        Object updateResult = identityHandler.handle(
                "application/identity/update", node(identity(9, false)), session);

        assertThat(updateResult).isEqualTo(Map.of("authenticated", false, "identityEpoch", 9L));
        assertThat(catalogs.current(trustedConnectionMessage())).isEmpty();
        assertThat(contexts.current(trustedConnectionMessage())).isEmpty();
        assertThat(identities.isAuthenticated("ws-1")).isFalse();
    }

    @Test
    void identityHandlerRequiresTrustedHandshakeAttributesAndKnownMethod() {
        WebSocketSession untrusted = mock(WebSocketSession.class);
        when(untrusted.getAttributes()).thenReturn(Map.of());
        when(untrusted.getId()).thenReturn("ws-untrusted");

        assertThatThrownBy(() -> identityHandler.handle(
                "application/identity/bind", node(identity(8, true)), untrusted))
                .isInstanceOfSatisfying(JsonRpcException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(error.getMessage()).isEqualTo("Invalid application identity parameters");
                });
        assertThatThrownBy(() -> identityHandler.handle(
                "application/identity/unknown", node(identity(8, true)), session))
                .isInstanceOfSatisfying(JsonRpcException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(error.getMessage()).isEqualTo("Invalid application identity parameters");
                });
    }

    @Test
    void handlersRejectAttributesThatAreNotBackedByTheFinalizedConnectionRegistry() {
        when(connections.findByDesktopSessionId("desktop-session-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> identityHandler.handle(
                "application/identity/bind", node(identity(8, true)), session))
                .isInstanceOfSatisfying(JsonRpcException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(error.getMessage()).isEqualTo("Invalid application identity parameters");
                });
        assertThat(identities.isAuthenticated("ws-1")).isFalse();
    }

    @Test
    void catalogHandlerOwnsRegisterUpdateAndContextPublish() throws Exception {
        identityHandler.handle(
                "application/identity/bind", node(identity(8, true)), session);
        assertThat(catalogHandler.methods()).containsExactlyInAnyOrder(
                "application/catalog/register",
                "application/catalog/update",
                "application/context/publish");

        Object registered = catalogHandler.handle(
                "application/catalog/register", node(catalogMessage(1, 1, catalogPayload())), session);
        Object updated = catalogHandler.handle(
                "application/catalog/update", node(catalogMessage(2, 2, catalogPayload())), session);
        Object published = catalogHandler.handle(
                "application/context/publish", node(contextMessage(3, 2, contextPayload())), session);

        assertThat(registered).isEqualTo(Map.of("catalogEpoch", 1L));
        assertThat(updated).isEqualTo(Map.of("catalogEpoch", 2L));
        assertThat(published).isEqualTo(Map.of("catalogEpoch", 2L, "contextSequence", 3L));
    }

    @Test
    void handlersRejectPayloadConnectionDriftBeforeRegistryMutation() throws Exception {
        identityHandler.handle(
                "application/identity/bind", node(identity(8, true)), session);
        ApplicationCatalogMessage drifted = new ApplicationCatalogMessage(
                "1.0", "desktop-other", "desktop-session-1", "auth-session-1", 8, 1,
                "2026-07-17T00:00:00Z", "user-1", "tenant-1", "platform-1",
                1, 1, payloadSize(catalogPayload()), catalogPayload());

        assertThatThrownBy(() -> catalogHandler.handle(
                "application/catalog/register", node(drifted), session))
                .isInstanceOfSatisfying(JsonRpcException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(error.getMessage()).isEqualTo("Invalid application catalog parameters");
                });
        assertThat(catalogs.current(trustedConnectionMessage())).isEmpty();
    }

    @Test
    void handlersMapClientAndStateFailuresToRedactedInvalidParams() throws Exception {
        JsonNode malformed = ApplicationProtocol.objectNode().put("identityEpoch", "secret-wrong-type");

        assertThatThrownBy(() -> identityHandler.handle(
                "application/identity/bind", malformed, session))
                .isInstanceOfSatisfying(JsonRpcException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(error.getMessage()).isEqualTo("Invalid application identity parameters");
                    assertThat(error.getMessage()).doesNotContain("secret-wrong-type");
                });

        identityHandler.handle("application/identity/bind", node(identity(8, true)), session);
        assertThatThrownBy(() -> catalogHandler.handle(
                "application/catalog/register", ApplicationProtocol.objectNode().put("payloadSize", "secret"), session))
                .isInstanceOfSatisfying(JsonRpcException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS);
                    assertThat(error.getMessage()).isEqualTo("Invalid application catalog parameters");
                    assertThat(error.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void mutationHandlersHoldTheFinalizedConnectionLock() throws Exception {
        TrustedDesktopConnection finalized = trustedConnectionMessage();
        ApplicationIdentityRegistry lockedIdentities = mock(ApplicationIdentityRegistry.class);
        ApplicationCatalogRegistry lockedCatalogs = mock(ApplicationCatalogRegistry.class);
        ApplicationPageContextRegistry lockedContexts = mock(ApplicationPageContextRegistry.class);
        when(connections.findByDesktopSessionId("desktop-session-1")).thenReturn(Optional.of(finalized));
        when(lockedIdentities.current(finalized)).thenReturn(Optional.of(new com.wzx.babiq.server.application.auth.TrustedBusinessIdentity(
                "reservation-1", "ws-1", "desktop-1", "desktop-session-1", "auth-session-1", 8,
                "user-1", "tenant-1", "platform-1", Set.of("lawyer"), Set.of("framework:read"))));
        when(lockedIdentities.update(
                org.mockito.ArgumentMatchers.eq(finalized),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(invocation -> {
                    assertThat(Thread.holdsLock(finalized)).isFalse();
                    synchronized (finalized) {
                        invocation.<Runnable>getArgument(2).run();
                    }
                    return Optional.empty();
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(Thread.holdsLock(finalized)).isTrue();
            return null;
        }).when(lockedCatalogs).clear(finalized);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(Thread.holdsLock(finalized)).isTrue();
            return null;
        }).when(lockedContexts).clear(finalized);
        when(lockedCatalogs.register(org.mockito.ArgumentMatchers.eq(finalized), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    assertThat(Thread.holdsLock(finalized)).isTrue();
                    return new ApplicationCatalogRegistry.CatalogSnapshot(
                            finalized, 1, catalogPayload(), true);
                });
        ApplicationIdentityProtocolHandler lockedIdentityHandler = new ApplicationIdentityProtocolHandler(
                lockedIdentities, lockedCatalogs, lockedContexts, connections);
        ApplicationCatalogProtocolHandler lockedCatalogHandler = new ApplicationCatalogProtocolHandler(
                lockedIdentities, lockedCatalogs, lockedContexts, connections);

        lockedIdentityHandler.handle("application/identity/update", node(identity(9, false)), session);
        lockedCatalogHandler.handle("application/catalog/register", node(catalogMessage(1, 1, catalogPayload())), session);

        verify(lockedIdentities).update(
                org.mockito.ArgumentMatchers.eq(finalized),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(lockedCatalogs).clear(finalized);
        verify(lockedContexts).clear(finalized);
    }

    @Test
    void identityUpdateKeepsTransitionClosedThroughSnapshotCleanup() throws Exception {
        identityHandler.handle("application/identity/bind", node(identity(8, true)), session);
        catalogs.register(trustedConnectionMessage(), catalogMessage(1, 1, catalogPayload()));
        contexts.publish(trustedConnectionMessage(), contextMessage(2, 1, contextPayload()));

        identityHandler.handle("application/identity/update", node(identity(9, false)), session);

        assertThat(identities.current(trustedConnectionMessage())).isEmpty();
        assertThat(catalogs.current(trustedConnectionMessage())).isEmpty();
        assertThat(contexts.current(trustedConnectionMessage())).isEmpty();
    }

    @Test
    void listenerInfrastructureFailureDoesNotFailCommittedUpdateAndSnapshotsAreCleared() throws Exception {
        ApplicationIdentityRegistry failingIdentities = new ApplicationIdentityRegistry(
                (trustedConnection, oldIdentity, newIdentity) -> {
                    throw new IllegalStateException("listener secret failure");
                });
        ApplicationCatalogRegistry retainedCatalogs = mock(ApplicationCatalogRegistry.class);
        ApplicationPageContextRegistry retainedContexts = mock(ApplicationPageContextRegistry.class);
        ApplicationIdentityProtocolHandler handler = new ApplicationIdentityProtocolHandler(
                failingIdentities, retainedCatalogs, retainedContexts, connections);
        failingIdentities.bind(trustedConnectionMessage(), identity(8, true));

        Object result = handler.handle(
                "application/identity/update", node(identity(9, false)), session);

        assertThat(result).isEqualTo(Map.of("authenticated", false, "identityEpoch", 9L));
        verify(retainedCatalogs).clear(trustedConnectionMessage());
        verify(retainedContexts).clear(trustedConnectionMessage());
        assertThat(failingIdentities.current(trustedConnectionMessage())).isEmpty();
    }

    @Test
    void reconciliationFailureDoesNotTurnACommittedIdentityBindIntoAnError() {
        ApplicationActionReconciliationService reconciliation = mock(ApplicationActionReconciliationService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("secret reconciliation payload"))
                .when(reconciliation).reconcile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        @SuppressWarnings("unchecked")
        ObjectProvider<ApplicationActionReconciliationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(reconciliation);
        ApplicationIdentityProtocolHandler handler = new ApplicationIdentityProtocolHandler(
                identities, catalogs, contexts, connections, provider);

        Object result = handler.handle("application/identity/bind", node(identity(8, true)), session);

        assertThat(result).isEqualTo(Map.of("authenticated", true, "identityEpoch", 8L));
        assertThat(identities.current(trustedConnectionMessage())).isPresent();
    }

    private WebSocketSession trustedSession() {
        WebSocketSession trusted = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(BusinessDesktopHandshakeInterceptor.RESERVATION_ID_ATTRIBUTE, "reservation-1");
        attributes.put(BusinessDesktopHandshakeInterceptor.DESKTOP_INSTANCE_ID_ATTRIBUTE, "desktop-1");
        attributes.put(BusinessDesktopHandshakeInterceptor.DESKTOP_SESSION_ID_ATTRIBUTE, "desktop-session-1");
        when(trusted.getAttributes()).thenReturn(attributes);
        when(trusted.getId()).thenReturn("ws-1");
        return trusted;
    }

    private TrustedDesktopConnection trustedConnectionMessage() {
        return new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "desktop-session-1", "ws-1");
    }

    private ApplicationIdentityMessage identity(long epoch, boolean authenticated) {
        return new ApplicationIdentityMessage(
                "1.0", "desktop-1", "desktop-session-1", authenticated ? "auth-session-1" : null,
                epoch, epoch, "2026-07-17T00:00:00Z",
                authenticated ? "user-1" : null,
                authenticated ? "tenant-1" : null,
                authenticated ? "platform-1" : null,
                authenticated, authenticated ? Set.of("lawyer") : Set.of(),
                authenticated ? Set.of("framework:read") : Set.of());
    }

    private ApplicationCatalogMessage catalogMessage(long sequence, long catalogEpoch, JsonNode payload) {
        return new ApplicationCatalogMessage(
                "1.0", "desktop-1", "desktop-session-1", "auth-session-1", 8, sequence,
                "2026-07-17T00:00:00Z", "user-1", "tenant-1", "platform-1",
                catalogEpoch, sequence, payloadSize(payload), payload);
    }

    private ApplicationCatalogMessage contextMessage(long sequence, long catalogEpoch, JsonNode payload) {
        return catalogMessage(sequence, catalogEpoch, payload);
    }

    private JsonNode catalogPayload() {
        var payload = ApplicationProtocol.objectNode();
        payload.putObject("actions").putObject("read")
                .put("enabled", true)
                .putArray("requiredPermissions").add("framework:read");
        return payload;
    }

    private JsonNode contextPayload() {
        return ApplicationProtocol.objectNode().put("pageType", "framework-demo");
    }

    private int payloadSize(JsonNode payload) {
        return payload.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private JsonNode node(Object value) {
        return objectMapper.valueToTree(value);
    }
}
