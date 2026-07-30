package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessOaStartupRestoreTest {

    @Test
    void new_child_projects_latest_detached_session_without_writing_or_issuing_attach_handle() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        repository.put(detached("old-child", 5, "credential-ref"));
        FinalizedConnection finalized = finalizedConnection("new-child", "ws-new");
        BusinessOaAuthenticationService service = service(
                repository, fixture, gateway, finalized.registry());
        TrustedDesktopConnection newChild = finalized.connection();

        BusinessAuthDtos.Session projection = service.session(newChild);

        assertThat(projection.state()).isEqualTo("DETACHED");
        assertThat(projection.generation()).isEqualTo(5);
        assertThat(projection.canRestore()).isTrue();
        assertThat(projection.canAttach()).isFalse();
        assertThat(projection.attachHandle()).isNull();
        assertThat(repository.record().desktopSessionId()).isEqualTo("old-child");
        assertThat(repository.insertCount).isZero();
        assertThat(repository.rebindCount).isZero();
        verifyNoRemoteCalls(gateway);
    }

    @Test
    void startup_restore_atomically_rebinds_latest_detached_session_to_the_new_child() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String credentialRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("old-child", 5, credentialRef));
        OaAuthenticationGateway gateway = gatewayForSuccessfulRestore();
        FinalizedConnection finalized = finalizedConnection("new-child", "ws-new");
        BusinessOaAuthenticationService service = service(
                repository, fixture, gateway, finalized.registry());
        TrustedDesktopConnection newChild = finalized.connection();

        BusinessAuthDtos.Session ready = service.restore(newChild);

        assertThat(ready.state()).isEqualTo("READY");
        assertThat(ready.generation()).isEqualTo(8);
        assertThat(ready.attachHandle()).isNull();
        assertThat(repository.rebindCount).isEqualTo(1);
        assertThat(repository.findByDesktopSession("desktop-1", "old-child")).isEmpty();
        assertThat(repository.findByDesktopSession("desktop-1", "new-child")).get()
                .satisfies(record -> {
                    assertThat(record.phase()).isEqualTo(OaSessionPhase.READY);
                    assertThat(record.generation()).isEqualTo(8);
                });
        verify(gateway).refresh(eq("tenant-1"), any(char[].class));
    }

    @Test
    void failed_startup_rebind_is_stale_and_never_refreshes() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        repository.put(detached("old-child", 5, "credential-ref"));
        repository.failRebind = true;
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        FinalizedConnection finalized = finalizedConnection("new-child", "ws-new");
        BusinessOaAuthenticationService service = service(repository, fixture, gateway,
                finalized.registry());

        assertThatThrownBy(() -> service.restore(finalized.connection()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_STALE");
        verify(gateway, never()).refresh(any(), any());
    }

    @Test
    void same_child_detached_session_must_use_attach_instead_of_startup_restore() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionCredentialStore credentials = fixture.credentials();
        String credentialRef = DurableOaSessionFixture.seedCredential(credentials,
                "auth-1", 1, "old-access".toCharArray(), "old-refresh".toCharArray());
        repository.put(detached("same-child", 5, credentialRef));
        OaAuthenticationGateway gateway = gatewayForSuccessfulRestore();
        FinalizedConnection finalized = finalizedConnection("same-child", "ws-new");
        BusinessOaAuthenticationService service = service(
                repository, fixture, gateway, finalized.registry());
        TrustedDesktopConnection sameChild = finalized.connection();

        BusinessAuthDtos.Session projection = service.session(sameChild);
        assertThat(projection.canAttach()).isTrue();
        assertThat(projection.canRestore()).isFalse();
        assertThat(projection.attachHandle()).isNotBlank();

        assertThatThrownBy(() -> service.restore(sameChild))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("BUSINESS_SESSION_NOT_ATTACHABLE");
        assertThat(repository.rebindCount).isZero();
        verify(gateway, never()).refresh(any(), any());
    }

    private static void verifyNoRemoteCalls(OaAuthenticationGateway gateway) {
        verify(gateway, never()).findTenantCandidates(any());
        verify(gateway, never()).login(any(), any());
        verify(gateway, never()).refresh(any(), any());
        verify(gateway, never()).loadPermissions(any(), any());
        verify(gateway, never()).logout(any(), any());
    }

    private static OaAuthenticationGateway gatewayForSuccessfulRestore() {
        OaAuthenticationGateway gateway = mock(OaAuthenticationGateway.class);
        when(gateway.refresh(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaCredential("new-access", "new-refresh", "user-1", 123L));
        when(gateway.loadPermissions(eq("tenant-1"), any(char[].class))).thenReturn(
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"), "user-1", "Lawyer", List.of()));
        return gateway;
    }

    private static BusinessOaAuthenticationService service(MemoryRepository repository,
                                                            DurableOaSessionFixture fixture,
                                                            OaAuthenticationGateway gateway,
                                                            BusinessDesktopConnectionRegistry connections) {
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        OaSessionCredentialStore credentials = fixture.credentials();
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        return new BusinessOaAuthenticationService(repository, gateway, persistence, sessions, installer,
                credentials, identities, new BusinessOaAttachHandleRegistry(repository), connections);
    }

    private static FinalizedConnection finalizedConnection(String childSessionId, String webSocketSessionId) {
        BusinessDesktopModeProperties mode = mock(BusinessDesktopModeProperties.class);
        when(mode.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry registry = new BusinessDesktopConnectionRegistry(mode);
        String reservation = registry.reserve("desktop-1", childSessionId);
        TrustedDesktopConnection connection = registry.finalizeReservation(
                reservation, "desktop-1", childSessionId, webSocketSessionId);
        return new FinalizedConnection(registry, connection);
    }

    private record FinalizedConnection(BusinessDesktopConnectionRegistry registry,
                                       TrustedDesktopConnection connection) {
    }

    private static OaSessionRecord detached(String childSessionId, long generation, String credentialRef) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord("auth-1", "desktop-1", childSessionId,
                "user-1", "tenant-1", "2", OaSessionPhase.DETACHED, generation,
                credentialRef, null, 1, null, now, now, null, now);
    }

    private static OaSessionRecord withLease(OaSessionRecord source, String childSessionId, long generation) {
        return new OaSessionRecord(source.authSessionId(), source.desktopInstanceId(), childSessionId,
                source.userId(), source.tenantId(), source.platformId(), source.phase(), generation,
                source.activeCredentialRef(), source.stagedCredentialRef(), source.credentialVersion(),
                source.installStartedAt(), source.installedAt(), source.detachedAt(), source.revokedAt(),
                Instant.parse("2026-07-27T04:01:00Z"), source.installationId(),
                source.installationOwnerDesktopInstanceId(), source.installationOwnerDesktopSessionId(),
                source.installationTargetGeneration(), source.installationExpiresAt());
    }

    private static final class MemoryRepository implements OaSessionRepository {
        private OaSessionRecord record;
        private int insertCount;
        private int rebindCount;
        private boolean failRebind;

        void put(OaSessionRecord value) { record = value; }
        OaSessionRecord record() { return record; }

        @Override public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return record != null && record.authSessionId().equals(authSessionId)
                    ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instanceId, String sessionId) {
            return record != null && record.desktopInstanceId().equals(instanceId)
                    && record.desktopSessionId().equals(sessionId) ? Optional.of(record) : Optional.empty();
        }
        @Override public Optional<OaSessionRecord> findLatestDetachedByDesktopInstanceId(String instanceId) {
            return record != null && record.desktopInstanceId().equals(instanceId)
                    && record.phase() == OaSessionPhase.DETACHED ? Optional.of(record) : Optional.empty();
        }
        @Override public OaSessionRecord insert(OaSessionRecord value) {
            insertCount++;
            record = value;
            return value;
        }
        @Override public OaSessionRecord update(OaSessionRecord value) { record = value; return value; }
        @Override public boolean compareAndSwapGeneration(String authSessionId, long expectedGeneration,
                                                          OaSessionRecord value) {
            if (record == null || !record.authSessionId().equals(authSessionId)
                    || record.generation() != expectedGeneration) return false;
            record = value;
            return true;
        }
        @Override public synchronized boolean compareAndSwapExact(
                OaSessionRecord expected, OaSessionRecord next) {
            if (!expected.equals(record)) return false;
            record = next;
            return true;
        }
        @Override public boolean compareAndSwapDetachedLease(
                String authSessionId, long expectedGeneration,
                String expectedDesktopInstanceId, String expectedDesktopSessionId,
                OaSessionRecord value) {
            if (failRebind || record == null || !record.authSessionId().equals(authSessionId)
                    || record.phase() != OaSessionPhase.DETACHED
                    || record.generation() != expectedGeneration
                    || !record.desktopInstanceId().equals(expectedDesktopInstanceId)
                    || !record.desktopSessionId().equals(expectedDesktopSessionId)) return false;
            rebindCount++;
            record = value;
            return true;
        }
        @Override public List<OaSessionRecord> listRecoverable() {
            return record == null ? List.of() : new ArrayList<>(List.of(record));
        }
    }
}
