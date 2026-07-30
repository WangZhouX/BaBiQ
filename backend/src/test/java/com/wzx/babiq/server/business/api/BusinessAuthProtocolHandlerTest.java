package com.wzx.babiq.server.business.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionResolver;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BusinessAuthProtocolHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void session_get_without_a_durable_session_is_read_only() {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        MemoryRepository repository = new MemoryRepository();
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        BusinessAuthProtocolHandler handler = handler(service(
                repository, gateway, finalized.registry()), connection);

        BusinessAuthDtos.Session result = (BusinessAuthDtos.Session) handler.handle(
                "business/auth/session/get", JSON.createObjectNode(), mock(WebSocketSession.class));

        assertThat(result.state()).isEqualTo("SIGNED_OUT");
        assertThat(result.authSessionId()).isNull();
        assertThat(repository.records).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void no_parameter_auth_methods_reject_fields_scalars_and_arrays() {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        BusinessAuthProtocolHandler handler = handler(service(
                new MemoryRepository(), mock(OaAuthenticationGateway.class),
                finalized.registry()), connection);
        WebSocketSession ws = mock(WebSocketSession.class);
        List<com.fasterxml.jackson.databind.JsonNode> invalidParams = List.of(
                node("tenantId", "attacker"), JSON.getNodeFactory().textNode("unexpected"),
                JSON.createArrayNode());

        for (String method : List.of(
                "business/auth/session/get", "business/auth/session/restore", "business/auth/logout")) {
            for (com.fasterxml.jackson.databind.JsonNode params : invalidParams) {
                assertThatThrownBy(() -> handler.handle(method, params, ws))
                        .as("method=%s params=%s", method, params.getNodeType())
                        .isInstanceOfSatisfying(com.wzx.babiq.server.api.error.JsonRpcException.class,
                                error -> assertThat(error.errorCode()).isEqualTo(
                                        com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS));
            }
        }
    }

    @Test
    void detached_session_get_returns_generation_and_an_opaque_attach_handle() {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        MemoryRepository repository = new MemoryRepository();
        repository.insert(detached("auth-1", "desktop-1", "session-1", 7));
        BusinessAuthProtocolHandler handler = handler(service(
                repository, mock(OaAuthenticationGateway.class), finalized.registry()), connection);

        BusinessAuthDtos.Session result = (BusinessAuthDtos.Session) handler.handle(
                "business/auth/session/get", JSON.createObjectNode(), mock(WebSocketSession.class));

        assertThat(result.state()).isEqualTo("DETACHED");
        assertThat(result.generation()).isEqualTo(7);
        assertThat(result.attachHandle()).isNotBlank()
                .doesNotContain("auth-1", "desktop-1", "session-1", "ws-1", "reservation-1");
        assertThat(result.canAttach()).isTrue();
        assertThat(result.canRestore()).isFalse();
        assertThat(result.toString()).doesNotContain(result.attachHandle());
    }

    @Test
    void session_attach_requires_only_a_non_blank_attach_handle() {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        MemoryRepository repository = new MemoryRepository();
        repository.insert(detached("auth-1", "desktop-1", "session-1", 7));
        BusinessAuthProtocolHandler handler = handler(service(
                repository, mock(OaAuthenticationGateway.class), finalized.registry()), connection);
        WebSocketSession ws = mock(WebSocketSession.class);

        assertThatThrownBy(() -> handler.handle(
                "business/auth/session/attach", JSON.createObjectNode(), ws))
                .isInstanceOfSatisfying(com.wzx.babiq.server.api.error.JsonRpcException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS));
        assertThatThrownBy(() -> handler.handle(
                "business/auth/session/attach", node("attachHandle", " "), ws))
                .isInstanceOfSatisfying(com.wzx.babiq.server.api.error.JsonRpcException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS));
        assertThatThrownBy(() -> handler.handle(
                "business/auth/session/attach",
                node("attachHandle", "opaque", "tenantId", "attacker"), ws))
                .isInstanceOfSatisfying(com.wzx.babiq.server.api.error.JsonRpcException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(com.wzx.babiq.server.api.error.JsonRpcErrorCode.INVALID_PARAMS));
    }

    @Test
    void logout_revokes_an_unconsumed_attach_handle() {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        MemoryRepository repository = new MemoryRepository();
        repository.insert(detached("auth-1", "desktop-1", "session-1", 7));
        BusinessAuthProtocolHandler handler = handler(service(
                repository, mock(OaAuthenticationGateway.class), finalized.registry()), connection);
        WebSocketSession ws = mock(WebSocketSession.class);
        BusinessAuthDtos.Session detached = (BusinessAuthDtos.Session) handler.handle(
                "business/auth/session/get", JSON.createObjectNode(), ws);

        handler.handle("business/auth/logout", JSON.createObjectNode(), ws);

        assertThatThrownBy(() -> handler.handle("business/auth/session/attach",
                node("attachHandle", detached.attachHandle()), ws))
                .isInstanceOfSatisfying(com.wzx.babiq.server.api.error.JsonRpcException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(
                                com.wzx.babiq.server.api.error.JsonRpcErrorCode.BUSINESS_SESSION_NOT_ATTACHABLE));
    }

    @Test
    void candidate_handle_is_opaque_single_use_and_login_response_contains_no_credentials() throws Exception {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        MemoryRepository repository = new MemoryRepository();
        OaAuthenticationGateway gateway = new OaAuthenticationGateway() {
            @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) {
                return List.of(new OaAuthDtos.OaTenantCandidate("user-1", "tenant-1", 2, "Firm", 1, null, account));
            }
            @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) {
                return new OaAuthDtos.OaCredential("access-token", "refresh-token", "user-1", 123L);
            }
            @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] refreshToken) { return null; }
            @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] accessToken) {
                return new OaAuthDtos.OaPermissionSnapshot(List.of("framework:read"), List.of("lawyer"), "user-1", "Lawyer", List.of());
            }
            @Override public void logout(String tenantId, char[] accessToken) { }
        };
        BusinessOaAuthenticationService service = service(
                repository, gateway, finalized.registry());
        BusinessDesktopConnectionResolver resolver = mock(BusinessDesktopConnectionResolver.class);
        when(resolver.requireFinalized(any())).thenReturn(connection);
        BusinessAuthProtocolHandler handler = new BusinessAuthProtocolHandler(service, resolver);
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn("ws-1");

        BusinessAuthDtos.TenantCandidates candidates = (BusinessAuthDtos.TenantCandidates) handler.handle("business/auth/tenant-candidates",
                node("account", "alice"), ws);
        String candidateId = candidates.candidates().get(0).candidateId();
        assertThat(candidateId).doesNotContain("tenant-1").doesNotContain("user-1");

        BusinessAuthDtos.Session result = (BusinessAuthDtos.Session) handler.handle("business/auth/login",
                node("account", "alice", "candidateId", candidateId, "password", "Abcd1234"), ws);
        String json = JSON.writeValueAsString(result);
        assertThat(json).doesNotContain("access-token").doesNotContain("refresh-token").doesNotContain("secretRef");
        assertThat(result.state()).isEqualTo("READY");
        assertThatThrownBy(() -> handler.handle("business/auth/login",
                node("account", "alice", "candidateId", candidateId, "password", "Abcd1234"), ws))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
    }

    @Test
    void login_requires_account_and_binds_it_to_the_candidate_ticket() throws Exception {
        FinalizedConnection finalized = finalizedConnection();
        TrustedDesktopConnection connection = finalized.connection();
        MemoryRepository repository = new MemoryRepository();
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.findTenantCandidates("alice")).thenReturn(List.of(
                new OaAuthDtos.OaTenantCandidate("user-1", "tenant-1", 2, "Firm", 1, null, "alice")));
        when(gateway.login(any(), any())).thenReturn(
                new OaAuthDtos.OaCredential("access-token", "refresh-token", "user-1", 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any())).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()));
        BusinessAuthProtocolHandler handler = handler(
                service(repository, gateway, finalized.registry()), connection);
        WebSocketSession ws = mock(WebSocketSession.class);

        BusinessAuthDtos.TenantCandidates candidates = (BusinessAuthDtos.TenantCandidates) handler.handle(
                "business/auth/tenant-candidates", node("account", "alice"), ws);
        String candidateId = candidates.candidates().getFirst().candidateId();

        assertThatThrownBy(() -> handler.handle("business/auth/login",
                node("candidateId", candidateId, "password", "Abcd1234"), ws))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
        assertThatThrownBy(() -> handler.handle("business/auth/login",
                node("account", "bob", "candidateId", candidateId, "password", "Abcd1234"), ws))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
        verify(gateway, never()).login(any(), any());
    }

    @Test
    void login_rejects_client_identity_overrides() {
        FinalizedConnection finalized = finalizedConnection();
        BusinessOaAuthenticationService service = service(
                new MemoryRepository(), mock(OaAuthenticationGateway.class), finalized.registry());
        BusinessDesktopConnectionResolver resolver = mock(BusinessDesktopConnectionResolver.class);
        when(resolver.requireFinalized(any())).thenReturn(finalized.connection());
        BusinessAuthProtocolHandler handler = new BusinessAuthProtocolHandler(service, resolver);
        assertThatThrownBy(() -> handler.handle("business/auth/login", node(
                "candidateId", "opaque", "password", "Abcd1234", "tenantId", "attacker"), mock(WebSocketSession.class)))
                .isInstanceOf(com.wzx.babiq.server.api.error.JsonRpcException.class);
    }

    private static BusinessOaAuthenticationService service(MemoryRepository repository,
                                                            OaAuthenticationGateway gateway,
                                                            BusinessDesktopConnectionRegistry connections) {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(identities, catalogs, contexts, persistence, sessions);
        return new BusinessOaAuthenticationService(
                repository, gateway, persistence, sessions, installer, credentials, identities,
                new BusinessOaAttachHandleRegistry(repository), connections);
    }

    private static FinalizedConnection finalizedConnection() {
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry(mode);
        String reservation = registry.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservation, "desktop-1", "session-1", "ws-1");
        return new FinalizedConnection(registry, connection);
    }

    private record FinalizedConnection(BusinessDesktopConnectionRegistry registry,
                                       TrustedDesktopConnection connection) {
    }

    private static BusinessAuthProtocolHandler handler(BusinessOaAuthenticationService service,
                                                        TrustedDesktopConnection connection) {
        BusinessDesktopConnectionResolver resolver = mock(BusinessDesktopConnectionResolver.class);
        when(resolver.requireFinalized(any())).thenReturn(connection);
        return new BusinessAuthProtocolHandler(service, resolver);
    }

    private static ObjectNode node(String... values) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < values.length; i += 2) node.put(values[i], values[i + 1]);
        return node;
    }

    private static OaSessionRecord detached(String authSessionId, String desktopInstanceId,
                                            String desktopSessionId, long generation) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord(authSessionId, desktopInstanceId, desktopSessionId,
                "user-1", "tenant-1", "2", OaSessionPhase.DETACHED, generation,
                "keystore://business.oa." + authSessionId + "/test-detached",
                null, 1, null, now, now, null, now);
    }

    private static final class MemoryRepository implements OaSessionRepository {
        private final Map<String, OaSessionRecord> records = new HashMap<>();
        @Override public Optional<OaSessionRecord> findByAuthSessionId(String id) { return Optional.ofNullable(records.get(id)); }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instance, String session) { return records.values().stream().filter(r -> r.desktopInstanceId().equals(instance) && r.desktopSessionId().equals(session)).findFirst(); }
        @Override public OaSessionRecord insert(OaSessionRecord record) { records.put(record.authSessionId(), record); return record; }
        @Override public OaSessionRecord update(OaSessionRecord record) { records.put(record.authSessionId(), record); return record; }
        @Override public boolean compareAndSwapGeneration(String id, long expected, OaSessionRecord record) { OaSessionRecord current = records.get(id); if (current == null || current.generation() != expected) return false; records.put(id, record); return true; }
        @Override public synchronized boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) { OaSessionRecord current = records.get(expected.authSessionId()); if (!expected.equals(current)) return false; records.put(expected.authSessionId(), next); return true; }
        @Override public List<OaSessionRecord> listRecoverable() { return List.copyOf(records.values()); }
    }
}
