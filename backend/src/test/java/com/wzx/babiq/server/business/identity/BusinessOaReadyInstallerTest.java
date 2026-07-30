package com.wzx.babiq.server.business.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.api.BusinessJsonRpcAccessPolicy;
import com.wzx.babiq.server.application.auth.ApplicationInstallationLease;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.session.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessOaReadyInstallerTest {

    @Test
    void post_bind_stays_closed_while_catalog_installation_is_blocked() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        BlockingCatalogRegistry catalogs = new BlockingCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        GateHarness harness = gateHarness(repository, identities, catalogs, contexts);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ReadyOaSessionLease> installation = executor.submit(() -> installReady(harness));

        try {
            assertThat(catalogs.installationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(harness.policy().isAllowed("thread/list", "ws-1")).isFalse();

            catalogs.releaseInstallation.countDown();
            ReadyOaSessionLease lease = installation.get(5, TimeUnit.SECONDS);
            assertThat(harness.sessions().isCurrent(lease)).isTrue();
            assertThat(harness.policy().isAllowed("thread/list", "ws-1")).isTrue();
        } finally {
            catalogs.releaseInstallation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void post_bind_stays_closed_while_durable_activation_is_blocked() throws Exception {
        BlockingActivationRepository repository = new BlockingActivationRepository();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        GateHarness harness = gateHarness(repository, identities, catalogs, contexts);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ReadyOaSessionLease> installation = executor.submit(() -> installReady(harness));

        try {
            assertThat(repository.activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(harness.policy().isAllowed("thread/list", "ws-1")).isFalse();

            repository.releaseActivation.countDown();
            ReadyOaSessionLease lease = installation.get(5, TimeUnit.SECONDS);
            assertThat(harness.sessions().isCurrent(lease)).isTrue();
            assertThat(harness.policy().isAllowed("thread/list", "ws-1")).isTrue();
        } finally {
            repository.releaseActivation.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void post_bind_stays_closed_after_durable_ready_before_live_lease_publication() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        GateHarness harness = gateHarness(repository, identities, catalogs, contexts);
        CountDownLatch publicationStarted = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ReadyOaSessionLease> installation = executor.submit(() -> harness.installer().install(
                harness.connection(), harness.staged(), "user-1", "tenant-1", "2", permissions(),
                (ready, commitProjections) -> {
                    publicationStarted.countDown();
                    await(releasePublication, "READY publication");
                    return harness.connections().withFinalized(harness.connection(), () -> {
                        commitProjections.run();
                        return harness.sessions().captureReady(ready, harness.connection());
                    });
                }));

        try {
            assertThat(publicationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                    .isEqualTo(OaSessionPhase.READY);
            assertThat(harness.policy().isAllowed("thread/list", "ws-1")).isFalse();

            releasePublication.countDown();
            ReadyOaSessionLease lease = installation.get(5, TimeUnit.SECONDS);
            assertThat(harness.sessions().isCurrent(lease)).isTrue();
            assertThat(harness.policy().isAllowed("thread/list", "ws-1")).isTrue();
        } finally {
            releasePublication.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void installs_identity_catalog_context_before_publishing_ready_session() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        TrustedDesktopConnection connection = new TrustedDesktopConnection("reservation-1", "desktop-1", "session-1", "ws-1");
        repository.insert(OaSessionRecord.signedOut("auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord authenticating = sessions.transition(
                "auth-1", 0, BusinessOaSessionState.AUTHENTICATING);
        OaSessionRecord staged = persistence.stage("auth-1", authenticating.generation(),
                "access-token".toCharArray(), "refresh-token".toCharArray());

        AtomicBoolean publishObservedInstalledProjections = new AtomicBoolean();
        ReadyOaSessionLease lease = installer.install(
                connection,
                staged,
                "user-1",
                "tenant-1",
                "2",
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"), "user-1", "Lawyer", List.of()),
                (ready, commitProjections) -> {
                    assertThat(identities.current(connection)).isEmpty();
                    assertThat(catalogs.current(connection)).isEmpty();
                    assertThat(contexts.current(connection)).isEmpty();
                    commitProjections.run();
                    assertThat(identities.current(connection)).isPresent();
                    assertThat(catalogs.current(connection)).isPresent();
                    assertThat(contexts.current(connection)).isPresent();
                    publishObservedInstalledProjections.set(true);
                    return sessions.captureReady(ready, connection);
                });

        assertThat(repository.findByAuthSessionId("auth-1").orElseThrow().phase())
                .isEqualTo(OaSessionPhase.READY);
        assertThat(sessions.isCurrent(lease)).isTrue();
        assertThat(publishObservedInstalledProjections).isTrue();
        assertThat(identities.current(connection)).isPresent();
        assertThat(identities.current(connection).orElseThrow().identityEpoch())
                .isEqualTo(lease.generation());
        assertThat(catalogs.current(connection)).isPresent();
        assertThat(contexts.current(connection)).isPresent();
        assertThat(identities.current(connection).orElseThrow().userId()).isEqualTo("user-1");

        ApplicationInstallationLease installation = identities.installationLease(connection).orElseThrow();
        assertThat(installation.installationId()).isEqualTo(staged.installationId());
        assertThat(installation.owner()).isEqualTo(connection);
        assertThat(installation.targetGeneration()).isEqualTo(staged.installationTargetGeneration());
        assertThat(installation.expiresAt()).isEqualTo(staged.installationExpiresAt());
        assertThat(catalogs.current(connection).orElseThrow().installationLease()).isEqualTo(installation);
        assertThat(contexts.current(connection).orElseThrow().installationLease()).isEqualTo(installation);
    }

    @Test
    void ready_install_projects_only_allowlisted_menu_paths_into_trusted_identity() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        TrustedDesktopConnection connection =
                new TrustedDesktopConnection("reservation-1", "desktop-1", "session-1", "ws-1");
        repository.insert(OaSessionRecord.signedOut("auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord authenticating = fixture.sessions().transition(
                "auth-1", 0, BusinessOaSessionState.AUTHENTICATING);
        OaSessionRecord staged = fixture.persistence().stage(
                "auth-1", authenticating.generation(), "access-token".toCharArray(), "refresh-token".toCharArray());
        OaAuthDtos.OaPermissionSnapshot permissions = new OaAuthDtos.OaPermissionSnapshot(
                List.of("framework:read"), List.of("lawyer"), "user-1", "Lawyer",
                List.of(
                        Map.of("path", "/case"),
                        Map.of("path", "https://evil.example"),
                        Map.of("children", List.of(Map.of("url", "/team")))));

        installer.install(connection, staged, "user-1", "tenant-1", "2", permissions,
                (ready, commit) -> {
                    commit.run();
                    return fixture.sessions().captureReady(ready, connection);
                });

        assertThat(identities.current(connection).orElseThrow().navigationPaths())
                .containsExactlyInAnyOrder("/case", "/team");
    }

    @Test
    void failed_old_install_does_not_clear_a_newer_installation_projection() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        OaSessionPersistenceService persistence = fixture.persistence();
        BusinessOaSessionRegistry sessions = fixture.sessions();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ReplacingContextRegistry contexts = new ReplacingContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, persistence, sessions);
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        repository.insert(OaSessionRecord.signedOut(
                "auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord authenticating = sessions.transition(
                "auth-1", 0, BusinessOaSessionState.AUTHENTICATING);
        OaSessionRecord staged = persistence.stage(
                "auth-1", authenticating.generation(), connection,
                "access-token".toCharArray(), "refresh-token".toCharArray());

        assertThatThrownBy(() -> installer.install(
                connection, staged, "user-1", "tenant-1", "2",
                new OaAuthDtos.OaPermissionSnapshot(
                        List.of("framework:read"), List.of("lawyer"),
                        "user-1", "Lawyer", List.of()),
                (ready, commitProjections) -> {
                    commitProjections.run();
                    return sessions.captureReady(ready, connection);
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("old installation lost the race");

        assertThat(identities.installationLease(connection).orElseThrow().installationId())
                .isEqualTo("installation-new");
        assertThat(catalogs.current(connection)).isPresent();
        assertThat(contexts.current(connection)).isPresent();
    }

    @Test
    void exact_abort_clears_all_projections_without_publishing_identity_change() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        List<String> identityChanges = new ArrayList<>();
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry(
                (connection, oldIdentity, newIdentity) -> identityChanges.add("changed"));
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationInstallationLease installation = new ApplicationInstallationLease(
                "installation-1", connection, 12,
                Instant.parse("2026-07-27T04:01:30Z"));
        installFullProjection(identities, catalogs, contexts, connection, installation);

        installer.abort(connection, stagedAttempt(connection, installation));

        assertThat(identities.current(connection)).isEmpty();
        assertThat(catalogs.current(connection)).isEmpty();
        assertThat(contexts.current(connection)).isEmpty();
        assertThat(identityChanges).isEmpty();
    }

    @Test
    void old_abort_with_same_installation_id_and_stale_generation_preserves_new_full_projection() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        Instant expiresAt = Instant.parse("2026-07-27T04:01:30Z");
        ApplicationInstallationLease current = new ApplicationInstallationLease(
                "installation-reused", connection, 12, expiresAt);
        ApplicationInstallationLease stale = new ApplicationInstallationLease(
                "installation-reused", connection, 11, expiresAt);
        installFullProjection(identities, catalogs, contexts, connection, current);

        installer.abort(connection, stagedAttempt(connection, stale));

        assertFullProjectionPreserved(identities, catalogs, contexts, connection, current);
    }

    @Test
    void old_abort_with_same_installation_id_and_stale_expiry_preserves_new_full_projection() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationInstallationLease current = new ApplicationInstallationLease(
                "installation-reused", connection, 12,
                Instant.parse("2026-07-27T04:01:30Z"));
        ApplicationInstallationLease stale = new ApplicationInstallationLease(
                "installation-reused", connection, 12,
                Instant.parse("2026-07-27T04:01:29Z"));
        installFullProjection(identities, catalogs, contexts, connection, current);

        installer.abort(connection, stagedAttempt(connection, stale));

        assertFullProjectionPreserved(identities, catalogs, contexts, connection, current);
    }

    @Test
    void old_revoke_with_same_installation_id_and_stale_expiry_preserves_new_full_projection() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        ApplicationInstallationLease current = new ApplicationInstallationLease(
                "installation-reused", connection, 12,
                Instant.parse("2026-07-27T04:01:30Z"));
        ApplicationInstallationLease stale = new ApplicationInstallationLease(
                "installation-reused", connection, 12,
                Instant.parse("2026-07-27T04:01:29Z"));
        installFullProjection(identities, catalogs, contexts, connection, current);

        installer.revoke(connection, stale);

        assertFullProjectionPreserved(identities, catalogs, contexts, connection, current);
    }

    @Test
    void old_revoke_with_same_installation_id_and_stale_generation_preserves_new_full_projection() {
        MemoryRepository repository = new MemoryRepository();
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        ApplicationIdentityRegistry identities = new ApplicationIdentityRegistry();
        ApplicationCatalogRegistry catalogs = new ApplicationCatalogRegistry(identities);
        ApplicationPageContextRegistry contexts = new ApplicationPageContextRegistry(identities, catalogs);
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation-1", "desktop-1", "session-1", "ws-1");
        Instant expiresAt = Instant.parse("2026-07-27T04:01:30Z");
        ApplicationInstallationLease current = new ApplicationInstallationLease(
                "installation-reused", connection, 12, expiresAt);
        ApplicationInstallationLease stale = new ApplicationInstallationLease(
                "installation-reused", connection, 11, expiresAt);
        installFullProjection(identities, catalogs, contexts, connection, current);

        installer.revoke(connection, stale);

        assertFullProjectionPreserved(identities, catalogs, contexts, connection, current);
    }

    @Test
    void installation_id_only_abort_api_is_not_exposed() {
        boolean hasInstallationIdOnlyAbort = Arrays.stream(BusinessOaReadyInstaller.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("abort"))
                .anyMatch(method -> Arrays.equals(
                        method.getParameterTypes(),
                        new Class<?>[]{TrustedDesktopConnection.class, String.class}));

        assertThat(hasInstallationIdOnlyAbort).isFalse();
    }

    private static void installFullProjection(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            TrustedDesktopConnection connection,
            ApplicationInstallationLease installationLease) {
        identities.installServer(
                connection, installationLease, "auth-new", 12,
                "user-new", "tenant-new", "2",
                Set.of("lawyer"), Set.of("framework:read"));
        var catalog = new ObjectMapper().createObjectNode();
        catalog.putObject("actions");
        catalogs.installServer(connection, installationLease, 1, catalog);
        var context = new ObjectMapper().createObjectNode();
        context.put("pageType", "workbench-new");
        contexts.installServer(connection, installationLease, 1, 1, context);
        contexts.commitInstallation(connection, installationLease);
        catalogs.commitInstallation(connection, installationLease);
        identities.commitInstallation(connection, installationLease);
    }

    private static GateHarness gateHarness(
            MemoryRepository repository,
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts) {
        DurableOaSessionFixture fixture = DurableOaSessionFixture.memory(repository);
        BusinessDesktopModeProperties properties = mock(BusinessDesktopModeProperties.class);
        when(properties.acceptTimeout()).thenReturn(Duration.ofSeconds(10));
        BusinessDesktopConnectionRegistry connections = new BusinessDesktopConnectionRegistry(properties);
        String reservationId = connections.reserve("desktop-1", "session-1");
        TrustedDesktopConnection connection = connections.finalizeReservation(
                reservationId, "desktop-1", "session-1", "ws-1");
        repository.insert(OaSessionRecord.signedOut(
                "auth-1", "desktop-1", "session-1", Instant.now()));
        OaSessionRecord authenticating = fixture.sessions().transition(
                "auth-1", 0, BusinessOaSessionState.AUTHENTICATING);
        OaSessionRecord staged = fixture.persistence().stage(
                "auth-1", authenticating.generation(), connection,
                "access-token".toCharArray(), "refresh-token".toCharArray());
        BusinessOaReadyInstaller installer = new BusinessOaReadyInstaller(
                identities, catalogs, contexts, fixture.persistence(), fixture.sessions());
        BusinessJsonRpcAccessPolicy policy = new BusinessJsonRpcAccessPolicy(
                identities, connections, fixture.sessions());
        return new GateHarness(
                installer, fixture.sessions(), connections, connection, staged, policy);
    }

    private static ReadyOaSessionLease installReady(GateHarness harness) {
        return harness.installer().install(
                harness.connection(), harness.staged(), "user-1", "tenant-1", "2", permissions(),
                (ready, commitProjections) -> harness.connections().withFinalized(
                        harness.connection(), () -> {
                            commitProjections.run();
                            return harness.sessions().captureReady(ready, harness.connection());
                        }));
    }

    private static OaAuthDtos.OaPermissionSnapshot permissions() {
        return new OaAuthDtos.OaPermissionSnapshot(
                List.of("framework:read"), List.of("lawyer"),
                "user-1", "Lawyer", List.of());
    }

    private static void await(CountDownLatch latch, String barrier) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(barrier + " latch timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(barrier + " interrupted", interrupted);
        }
    }

    private record GateHarness(
            BusinessOaReadyInstaller installer,
            BusinessOaSessionRegistry sessions,
            BusinessDesktopConnectionRegistry connections,
            TrustedDesktopConnection connection,
            OaSessionRecord staged,
            BusinessJsonRpcAccessPolicy policy) {
    }

    private static final class BlockingCatalogRegistry extends ApplicationCatalogRegistry {
        private final CountDownLatch installationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseInstallation = new CountDownLatch(1);

        private BlockingCatalogRegistry(ApplicationIdentityRegistry identities) {
            super(identities);
        }

        @Override
        public synchronized CatalogSnapshot installServer(
                TrustedDesktopConnection connection,
                ApplicationInstallationLease installationLease,
                long catalogEpoch,
                com.fasterxml.jackson.databind.JsonNode payload) {
            installationStarted.countDown();
            await(releaseInstallation, "catalog installation");
            return super.installServer(
                    connection, installationLease, catalogEpoch, payload);
        }
    }

    private static OaSessionRecord stagedAttempt(
            TrustedDesktopConnection connection,
            ApplicationInstallationLease installationLease) {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        return new OaSessionRecord(
                "auth-stale", connection.desktopInstanceId(), connection.desktopSessionId(),
                "user-stale", "tenant-stale", "2", OaSessionPhase.INSTALLING,
                installationLease.targetGeneration(), null, "staged-ref", 1,
                now, null, null, null, now,
                installationLease.installationId(), connection.desktopInstanceId(),
                connection.desktopSessionId(), installationLease.targetGeneration(),
                installationLease.expiresAt());
    }

    private static void assertFullProjectionPreserved(
            ApplicationIdentityRegistry identities,
            ApplicationCatalogRegistry catalogs,
            ApplicationPageContextRegistry contexts,
            TrustedDesktopConnection connection,
            ApplicationInstallationLease expectedLease) {
        assertThat(identities.installationLease(connection)).contains(expectedLease);
        assertThat(identities.current(connection).orElseThrow().authSessionId()).isEqualTo("auth-new");
        assertThat(identities.current(connection).orElseThrow().userId()).isEqualTo("user-new");
        assertThat(catalogs.current(connection).orElseThrow().installationLease()).isEqualTo(expectedLease);
        assertThat(contexts.current(connection).orElseThrow().installationLease()).isEqualTo(expectedLease);
        assertThat(contexts.current(connection).orElseThrow().payload().path("pageType").textValue())
                .isEqualTo("workbench-new");
    }

    private static final class ReplacingContextRegistry extends ApplicationPageContextRegistry {
        private final ApplicationIdentityRegistry identities;
        private final ApplicationCatalogRegistry catalogs;

        private ReplacingContextRegistry(
                ApplicationIdentityRegistry identities,
                ApplicationCatalogRegistry catalogs) {
            super(identities, catalogs);
            this.identities = identities;
            this.catalogs = catalogs;
        }

        @Override
        public synchronized PageContextSnapshot installServer(
                TrustedDesktopConnection connection,
                ApplicationInstallationLease ignoredOldLease,
                long catalogEpoch,
                long contextSequence,
                com.fasterxml.jackson.databind.JsonNode payload) {
            clear(connection);
            catalogs.clear(connection);
            identities.clear(connection);
            ApplicationInstallationLease newer = new ApplicationInstallationLease(
                    "installation-new", connection, 99, Instant.now().plusSeconds(90));
            identities.installServer(
                    connection, newer, "auth-new", 2,
                    "user-new", "tenant-new", "2", Set.of("lawyer"), Set.of("framework:read"));
            var catalog = new ObjectMapper().createObjectNode();
            catalog.putObject("actions");
            catalogs.installServer(connection, newer, 2, catalog);
            super.installServer(
                    connection, newer, 2, 2, new ObjectMapper().createObjectNode());
            super.commitInstallation(connection, newer);
            catalogs.commitInstallation(connection, newer);
            identities.commitInstallation(connection, newer);
            throw new IllegalStateException("old installation lost the race");
        }
    }

    private static class MemoryRepository implements OaSessionRepository {
        private final Map<String, OaSessionRecord> records = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public Optional<OaSessionRecord> findByAuthSessionId(String id) { return Optional.ofNullable(records.get(id)); }
        @Override public Optional<OaSessionRecord> findByDesktopSession(String instance, String session) {
            return records.values().stream().filter(r -> r.desktopInstanceId().equals(instance)
                    && r.desktopSessionId().equals(session)).findFirst();
        }
        @Override public OaSessionRecord insert(OaSessionRecord record) { records.put(record.authSessionId(), record); return record; }
        @Override public OaSessionRecord update(OaSessionRecord record) { records.put(record.authSessionId(), record); return record; }
        @Override public boolean compareAndSwapGeneration(String id, long expected, OaSessionRecord record) {
            OaSessionRecord current = records.get(id);
            if (current == null || current.generation() != expected) return false;
            records.put(id, record); return true;
        }
        @Override public synchronized boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) {
            OaSessionRecord current = records.get(expected.authSessionId());
            if (!expected.equals(current)) return false;
            records.put(expected.authSessionId(), next);
            return true;
        }
        @Override public List<OaSessionRecord> listRecoverable() { return List.copyOf(records.values()); }
    }

    private static final class BlockingActivationRepository extends MemoryRepository {
        private final CountDownLatch activationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseActivation = new CountDownLatch(1);

        @Override
        public synchronized boolean compareAndSwapExact(
                OaSessionRecord expected,
                OaSessionRecord next) {
            if (expected.phase() == OaSessionPhase.INSTALLING
                    && next.phase() == OaSessionPhase.READY) {
                activationStarted.countDown();
                await(releaseActivation, "durable activation");
            }
            return super.compareAndSwapExact(expected, next);
        }
    }

}
