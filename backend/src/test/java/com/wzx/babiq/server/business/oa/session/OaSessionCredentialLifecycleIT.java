package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.business.identity.BusinessOaReadyInstaller;
import com.wzx.babiq.server.business.oa.client.OaAuthenticationGateway;
import com.wzx.babiq.server.settings.LocalKeyStoreSecretStore;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 使用真实 SQLite 与真实 JCEKS 验证 OA session 和凭据清理日志的跨资源生命周期。
 *
 * <p>SQLite 事务只能原子提交 session 与 cleanup journal；JCEKS 写删必须位于事务外，
 * 由耐久 journal 在进程中断或文件删除失败后继续收口。</p>
 */
@SpringBootTest
class OaSessionCredentialLifecycleIT {
    private static final String KEYSTORE_PASSWORD = "oa-lifecycle-test-password";
    private static final Instant NOW = Instant.parse("2026-07-28T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Path TEST_DB = Path.of(
            "target", "test-db", "oa-credential-lifecycle-" + UUID.randomUUID() + ".db")
            .toAbsolutePath();
    private static final Path TEST_KEYSTORE = Path.of(
            "target", "test-secrets", "oa-credential-lifecycle-" + UUID.randomUUID() + ".jceks")
            .toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", TEST_DB::toString);
    }

    @Autowired
    private SQLiteOaSessionRepository sqliteSessions;

    @Autowired
    private SQLiteBusinessOaSecretCleanupRepository sqliteCleanup;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void stage_rolls_back_session_cas_and_reserved_consumption_together() {
        Fixture fixture = fixture();
        String authSessionId = unique("stage-rollback");
        TrustedDesktopConnection owner = owner(authSessionId);
        OaSessionRecord authenticating = authenticating(authSessionId, owner);
        fixture.sessions.insert(authenticating);
        fixture.cleanup.failAfterConsume();
        fixture.secrets.failNextDelete();

        assertThatThrownBy(() -> fixture.persistence.stage(
                authSessionId, authenticating.generation(), owner,
                "stage-access".toCharArray(), "stage-refresh".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected cleanup consume failure");

        assertThat(fixture.sessions.findByAuthSessionId(authSessionId)).contains(authenticating);
        BusinessOaSecretCleanupRecord pending = cleanupRecord(authSessionId);
        assertThat(pending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(pending.attemptCount()).isEqualTo(1);
        assertCredentialReadable(fixture.credentials, pending.secretRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        assertThat(fixture.sessions.exactCasObservedInsideTransaction()).isTrue();
        assertThat(fixture.cleanup.consumeObservedInsideTransaction()).isTrue();
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.writeObservedOutsideTransaction()).isTrue();
        assertThat(fixture.secrets.deleteObservedOutsideTransaction()).isTrue();

        assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
        assertThat(sqliteCleanup.findBySecretRef(pending.secretRef())).isEmpty();
        assertCredentialMissing(fixture.credentials, pending.secretRef());
        assertCredentialMissing(reopenedCredentials(), pending.secretRef());
    }

    @Test
    void startup_recovery_drains_an_orphan_reserved_alias_without_touching_an_active_alias() {
        Fixture fixture = fixture();
        String orphanAuthSessionId = unique("orphan-reserved");
        String orphanRef = fixture.cleanupService.reserveAndWrite(
                orphanAuthSessionId, 1,
                "orphan-access".toCharArray(), "orphan-refresh".toCharArray(),
                "TEST_ORPHAN_RESERVED", null);
        assertThat(sqliteCleanup.findBySecretRef(orphanRef).orElseThrow().state())
                .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
        assertCredentialReadable(fixture.credentials, orphanRef);
        assertCredentialReadable(reopenedCredentials(), orphanRef);
        assertThat(fixture.sessions.existsCredentialReference(orphanRef)).isFalse();

        String activeAuthSessionId = unique("protected-active");
        TrustedDesktopConnection activeOwner = owner(activeAuthSessionId);
        String activeRef = fixture.cleanupService.reserveAndWrite(
                activeAuthSessionId, 1,
                "active-access".toCharArray(), "active-refresh".toCharArray(),
                "TEST_ACTIVE", null);
        OaSessionRecord ready = OaSessionRecord.ready(
                activeAuthSessionId,
                activeOwner.desktopInstanceId(),
                activeOwner.desktopSessionId(),
                activeRef,
                NOW);
        fixture.sessions.insert(ready);
        assertThat(sqliteCleanup.findBySecretRef(activeRef).orElseThrow().state())
                .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
        assertCredentialReadable(fixture.credentials, activeRef);
        assertCredentialReadable(reopenedCredentials(), activeRef);
        assertThat(fixture.sessions.existsCredentialReference(activeRef)).isTrue();

        new BusinessOaSessionRecoveryService(fixture.sessions, fixture.persistence).recover();

        assertThat(sqliteCleanup.findBySecretRef(orphanRef)).isEmpty();
        assertCredentialMissing(fixture.credentials, orphanRef);
        assertCredentialMissing(reopenedCredentials(), orphanRef);
        assertThat(fixture.sessions.findByAuthSessionId(activeAuthSessionId)).contains(ready);
        assertThat(sqliteCleanup.findBySecretRef(activeRef).orElseThrow().state())
                .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
        assertCredentialReadable(fixture.credentials, activeRef);
        assertCredentialReadable(reopenedCredentials(), activeRef);
    }

    @Test
    void activation_journal_failure_rolls_back_ready_publication() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(fixture, unique("activate-rollback"));
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.cleanup.failAfterUpsertDeletePending();

        assertThatThrownBy(() -> fixture.persistence.activate(
                installing.record.authSessionId(), installing.record.generation(),
                installing.record.installationId(), installing.owner,
                "user-new", "tenant-new", "2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected cleanup journal failure");

        assertThat(fixture.sessions.findByAuthSessionId(installing.record.authSessionId()))
                .contains(installing.record);
        assertThat(sqliteCleanup.findBySecretRef(installing.oldActiveRef)).isEmpty();
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, installing.record.stagedCredentialRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        assertThat(fixture.cleanup.upsertObservedInsideTransaction()).isTrue();
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.deleteCalls()).isZero();
    }

    @Test
    void activation_delete_failure_keeps_ready_and_delete_pending_journal() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(fixture, unique("activate-delete-failure"));
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.secrets.failNextDelete();

        OaSessionRecord ready = fixture.persistence.activate(
                installing.record.authSessionId(), installing.record.generation(),
                installing.record.installationId(), installing.owner,
                "user-new", "tenant-new", "2");

        assertThat(ready.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(ready.activeCredentialRef()).isEqualTo(installing.record.stagedCredentialRef());
        assertThat(fixture.sessions.findByAuthSessionId(ready.authSessionId())).contains(ready);
        BusinessOaSecretCleanupRecord pending = sqliteCleanup
                .findBySecretRef(installing.oldActiveRef).orElseThrow();
        assertThat(pending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(pending.attemptCount()).isEqualTo(1);
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, ready.activeCredentialRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.deleteObservedOutsideTransaction()).isTrue();

        assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
        assertThat(sqliteCleanup.findBySecretRef(installing.oldActiveRef)).isEmpty();
        assertCredentialMissing(fixture.credentials, installing.oldActiveRef);
        assertCredentialMissing(reopenedCredentials(), installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, ready.activeCredentialRef());
        assertCredentialReadable(reopenedCredentials(), ready.activeCredentialRef());
    }

    @Test
    void activation_post_commit_drain_failure_still_returns_committed_ready() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(
                fixture, unique("activate-drain-failure"));
        fixture.cleanup.failNextPendingScan();

        OaSessionRecord ready = fixture.persistence.activate(
                installing.record.authSessionId(), installing.record.generation(),
                installing.record.installationId(), installing.owner,
                "user-new", "tenant-new", "2");

        assertThat(ready.phase()).isEqualTo(OaSessionPhase.READY);
        assertThat(fixture.sessions.findByAuthSessionId(ready.authSessionId())).contains(ready);
        assertThat(sqliteCleanup.findBySecretRef(installing.oldActiveRef))
                .get()
                .extracting(BusinessOaSecretCleanupRecord::state)
                .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, ready.activeCredentialRef());

        assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
        assertThat(sqliteCleanup.findBySecretRef(installing.oldActiveRef)).isEmpty();
        assertCredentialMissing(reopenedCredentials(), installing.oldActiveRef);
        assertCredentialReadable(reopenedCredentials(), ready.activeCredentialRef());
    }

    @Test
    void recovery_uses_one_exact_cas_and_rolls_it_back_when_journal_fails() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(fixture, unique("recovery-rollback"));
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.cleanup.failAfterUpsertDeletePending();

        assertThatThrownBy(() -> fixture.persistence.recoverInstalling(installing.record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected cleanup journal failure");

        assertThat(fixture.sessions.findByAuthSessionId(installing.record.authSessionId()))
                .contains(installing.record);
        assertThat(sqliteCleanup.findBySecretRef(installing.record.stagedCredentialRef())).isEmpty();
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, installing.record.stagedCredentialRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.deleteCalls()).isZero();

        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        OaSessionRecord recovered = fixture.persistence.recoverInstalling(installing.record);
        assertThat(recovered.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(recovered.activeCredentialRef()).isEqualTo(installing.oldActiveRef);
        assertThat(recovered.stagedCredentialRef()).isNull();
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertCredentialMissing(fixture.credentials, installing.record.stagedCredentialRef());
        assertCredentialMissing(reopenedCredentials(), installing.record.stagedCredentialRef());
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(reopenedCredentials(), installing.oldActiveRef);
    }

    @Test
    void recovery_post_commit_drain_failure_still_returns_committed_terminal_state() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(
                fixture, unique("recovery-drain-failure"));
        fixture.cleanup.failNextPendingScan();

        OaSessionRecord recovered = fixture.persistence.recoverInstalling(installing.record);

        assertThat(recovered.phase()).isEqualTo(OaSessionPhase.DETACHED);
        assertThat(recovered.activeCredentialRef()).isEqualTo(installing.oldActiveRef);
        assertThat(recovered.stagedCredentialRef()).isNull();
        assertThat(fixture.sessions.findByAuthSessionId(recovered.authSessionId()))
                .contains(recovered);
        assertThat(sqliteCleanup.findBySecretRef(installing.record.stagedCredentialRef()))
                .get()
                .extracting(BusinessOaSecretCleanupRecord::state)
                .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertCredentialReadable(fixture.credentials, installing.record.stagedCredentialRef());

        assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
        assertThat(sqliteCleanup.findBySecretRef(installing.record.stagedCredentialRef())).isEmpty();
        assertCredentialMissing(reopenedCredentials(), installing.record.stagedCredentialRef());
        assertCredentialReadable(reopenedCredentials(), installing.oldActiveRef);
    }

    @Test
    void detach_journal_failure_rolls_back_session_transition() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(fixture, unique("detach-rollback"));
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.cleanup.failAfterUpsertDeletePending();

        assertThatThrownBy(() -> fixture.persistence.detach(installing.owner))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected cleanup journal failure");

        assertThat(fixture.sessions.findByAuthSessionId(installing.record.authSessionId()))
                .contains(installing.record);
        assertThat(sqliteCleanup.findBySecretRef(installing.record.stagedCredentialRef())).isEmpty();
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, installing.record.stagedCredentialRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.deleteCalls()).isZero();
    }

    @Test
    void abort_restore_journal_failure_rolls_back_detach() {
        Fixture fixture = fixture();
        InstallingFixture installing = prepareInstalling(fixture, unique("restore-abort-rollback"));
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.cleanup.failAfterUpsertDeletePending();

        assertThatThrownBy(() -> fixture.persistence.abortRestore(
                installing.owner,
                installing.record.authSessionId(),
                installing.record.generation(),
                installing.record.installationId(),
                installing.record.stagedCredentialRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected cleanup journal failure");

        assertThat(fixture.sessions.findByAuthSessionId(installing.record.authSessionId()))
                .contains(installing.record);
        assertThat(sqliteCleanup.findBySecretRef(installing.record.stagedCredentialRef())).isEmpty();
        assertCredentialReadable(fixture.credentials, installing.oldActiveRef);
        assertCredentialReadable(fixture.credentials, installing.record.stagedCredentialRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.deleteCalls()).isZero();
    }

    @Test
    void abort_login_after_detach_rolls_back_signed_out_when_journal_fails() {
        Fixture fixture = fixture();
        String authSessionId = unique("login-abort-rollback");
        TrustedDesktopConnection owner = owner(authSessionId);
        OaSessionRecord authenticating = authenticating(authSessionId, owner);
        fixture.sessions.insert(authenticating);
        OaSessionRecord installing = fixture.persistence.stage(
                authSessionId,
                authenticating.generation(),
                owner,
                "login-access".toCharArray(),
                "login-refresh".toCharArray());
        OaSessionRecord ready = fixture.persistence.activate(
                authSessionId,
                installing.generation(),
                installing.installationId(),
                owner,
                "user-login",
                "tenant-login",
                "2");
        OaSessionRecord detached = fixture.persistence.detach(owner);
        assertThat(detached.generation()).isEqualTo(authenticating.generation() + 2);
        assertThat(detached.activeCredentialRef()).isEqualTo(ready.activeCredentialRef());
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.cleanup.failAfterUpsertDeletePending();

        assertThatThrownBy(() -> fixture.persistence.abortLogin(
                owner,
                authSessionId,
                authenticating.generation(),
                installing.installationId(),
                installing.stagedCredentialRef()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected cleanup journal failure");

        assertThat(fixture.sessions.findByAuthSessionId(authSessionId)).contains(detached);
        assertThat(sqliteCleanup.findBySecretRef(detached.activeCredentialRef())).isEmpty();
        assertCredentialReadable(fixture.credentials, detached.activeCredentialRef());
        assertCredentialReadable(reopenedCredentials(), detached.activeCredentialRef());
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
    }

    @Test
    void revoke_delete_failure_keeps_signed_out_and_delete_pending() {
        Fixture fixture = fixture();
        String authSessionId = unique("revoke-delete-failure");
        TrustedDesktopConnection owner = owner(authSessionId);
        String activeRef = fixture.cleanupService.reserveAndWrite(
                authSessionId,
                1,
                "revoke-access".toCharArray(),
                "revoke-refresh".toCharArray(),
                "TEST_SEED",
                null);
        OaSessionRecord ready = OaSessionRecord.ready(
                authSessionId,
                owner.desktopInstanceId(),
                owner.desktopSessionId(),
                activeRef,
                NOW);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            fixture.sessions.insert(ready);
            assertThat(fixture.cleanup.consumeReserved(activeRef, authSessionId)).isTrue();
        });
        OaSessionRecord revoking = fixture.persistence.transition(
                authSessionId,
                ready.generation(),
                OaSessionPhase.REVOKING);
        fixture.sessions.resetExactCasCalls();
        fixture.transactionProbe.reset();
        fixture.secrets.failNextDelete();

        OaSessionRecord signedOut = fixture.persistence.revoke(
                authSessionId,
                revoking.generation());

        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(signedOut.activeCredentialRef()).isNull();
        assertThat(fixture.sessions.findByAuthSessionId(authSessionId)).contains(signedOut);
        BusinessOaSecretCleanupRecord pending = sqliteCleanup
                .findBySecretRef(activeRef)
                .orElseThrow();
        assertThat(pending.state()).isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertThat(pending.attemptCount()).isEqualTo(1);
        assertCredentialReadable(fixture.credentials, activeRef);
        assertThat(fixture.sessions.exactCasCalls()).isEqualTo(1);
        fixture.transactionProbe.assertSessionAndCleanupShareConnection();
        assertThat(fixture.secrets.deleteObservedOutsideTransaction()).isTrue();

        assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
        assertThat(sqliteCleanup.findBySecretRef(activeRef)).isEmpty();
        assertCredentialMissing(fixture.credentials, activeRef);
        assertCredentialMissing(reopenedCredentials(), activeRef);
    }

    @Test
    void automatic_terminalization_never_notifies_until_real_jceks_delete_retry_succeeds() {
        Fixture fixture = fixture();
        String authSessionId = unique("terminal-notification-delete");
        TrustedDesktopConnection owner = owner(authSessionId);
        String activeRef = fixture.cleanupService.reserveAndWrite(
                authSessionId,
                1,
                "terminal-access".toCharArray(),
                "terminal-refresh".toCharArray(),
                "TEST_SEED",
                null);
        OaSessionRecord ready = OaSessionRecord.ready(
                authSessionId,
                owner.desktopInstanceId(),
                owner.desktopSessionId(),
                activeRef,
                NOW);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            fixture.sessions.insert(ready);
            assertThat(fixture.cleanup.consumeReserved(activeRef, authSessionId)).isTrue();
        });
        BusinessOaSessionRegistry liveSessions =
                new BusinessOaSessionRegistry(fixture.sessions, fixture.persistence);
        ReadyOaSessionLease lease = liveSessions.captureReady(ready, owner);
        BusinessDesktopConnectionRegistry connections = mock(BusinessDesktopConnectionRegistry.class);
        ApplicationIdentityRegistry identities = mock(ApplicationIdentityRegistry.class);
        BusinessAuthStateNotifier notifier = mock(BusinessAuthStateNotifier.class);
        when(connections.findByWebSocketSessionId(owner.webSocketSessionId()))
                .thenReturn(Optional.of(owner));
        when(identities.current(owner)).thenReturn(Optional.empty());
        when(identities.installationLease(owner)).thenReturn(Optional.empty());
        BusinessOaAuthenticationService service = new BusinessOaAuthenticationService(
                fixture.sessions,
                mock(OaAuthenticationGateway.class),
                fixture.persistence,
                liveSessions,
                mock(BusinessOaReadyInstaller.class),
                fixture.credentials,
                identities,
                mock(BusinessOaAttachHandleRegistry.class),
                connections,
                notifier);
        fixture.secrets.failDeletes(2);

        BusinessOaSecretCleanupException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                BusinessOaSecretCleanupException.class,
                () -> service.terminate(
                        lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED));

        assertThat(failure.resultCode()).isEqualTo("SECRET_CLEANUP_INCOMPLETE");
        OaSessionRecord signedOut = fixture.sessions.findByAuthSessionId(authSessionId).orElseThrow();
        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(sqliteCleanup.findBySecretRef(activeRef)).get()
                .extracting(BusinessOaSecretCleanupRecord::state)
                .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertCredentialReadable(fixture.credentials, activeRef);
        verifyNoInteractions(notifier);

        assertThatCode(() -> service.terminate(
                lease, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED))
                .doesNotThrowAnyException();

        assertCredentialMissing(fixture.credentials, activeRef);
        assertThat(sqliteCleanup.findBySecretRef(activeRef)).isEmpty();
        verify(notifier, times(1)).signedOut(
                lease, signedOut, OaRemoteRequestException.TerminalReason.AUTH_EXPIRED);
    }

    @Test
    void terminal_transition_post_commit_drain_failure_still_returns_signed_out() {
        Fixture fixture = fixture();
        String authSessionId = unique("revoke-drain-failure");
        TrustedDesktopConnection owner = owner(authSessionId);
        String activeRef = fixture.cleanupService.reserveAndWrite(
                authSessionId,
                1,
                "revoke-access".toCharArray(),
                "revoke-refresh".toCharArray(),
                "TEST_SEED",
                null);
        OaSessionRecord ready = OaSessionRecord.ready(
                authSessionId,
                owner.desktopInstanceId(),
                owner.desktopSessionId(),
                activeRef,
                NOW);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            fixture.sessions.insert(ready);
            assertThat(fixture.cleanup.consumeReserved(activeRef, authSessionId)).isTrue();
        });
        OaSessionRecord revoking = fixture.persistence.transition(
                authSessionId,
                ready.generation(),
                OaSessionPhase.REVOKING);
        fixture.cleanup.failNextPendingScan();

        OaSessionRecord signedOut = fixture.persistence.revoke(
                authSessionId,
                revoking.generation());

        assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
        assertThat(fixture.sessions.findByAuthSessionId(authSessionId)).contains(signedOut);
        assertThat(sqliteCleanup.findBySecretRef(activeRef))
                .get()
                .extracting(BusinessOaSecretCleanupRecord::state)
                .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
        assertCredentialReadable(fixture.credentials, activeRef);

        assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
        assertThat(sqliteCleanup.findBySecretRef(activeRef)).isEmpty();
        assertCredentialMissing(reopenedCredentials(), activeRef);
    }

    @Test
    void login_activation_winning_close_leaves_promoted_ref_pending_until_retry_drain()
            throws Exception {
        Fixture fixture = fixture();
        String authSessionId = unique("login-activation-wins-close");
        TrustedDesktopConnection owner = owner(authSessionId);
        OaSessionRecord authenticating = authenticating(authSessionId, owner);
        fixture.sessions.insert(authenticating);
        OaSessionRecord installing = fixture.persistence.stage(
                authSessionId,
                authenticating.generation(),
                owner,
                "login-race-access".toCharArray(),
                "login-race-refresh".toCharArray());
        fixture.sessions.blockNextCloseTransition();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<OaSessionRecord> close = executor.submit(
                () -> fixture.persistence.detachBeforeCleanup(owner));

        try {
            assertThat(fixture.sessions.closeTransitionStarted().await(5, TimeUnit.SECONDS))
                    .isTrue();
            OaSessionRecord ready = fixture.persistence.activate(
                    authSessionId,
                    installing.generation(),
                    installing.installationId(),
                    owner,
                    "user-login-race",
                    "tenant-login-race",
                    "2");
            assertThat(ready.phase()).isEqualTo(OaSessionPhase.READY);
            assertThat(ready.activeCredentialRef()).isEqualTo(installing.stagedCredentialRef());

            fixture.sessions.releaseCloseTransition();
            OaSessionRecord signedOut = close.get(5, TimeUnit.SECONDS);

            assertThat(signedOut.phase()).isEqualTo(OaSessionPhase.SIGNED_OUT);
            assertThat(signedOut.activeCredentialRef()).isNull();
            assertThat(fixture.sessions.findByAuthSessionId(authSessionId)).contains(signedOut);
            assertThat(sqliteCleanup.findBySecretRef(ready.activeCredentialRef()))
                    .get()
                    .extracting(BusinessOaSecretCleanupRecord::state)
                    .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
            assertCredentialReadable(reopenedCredentials(), ready.activeCredentialRef());

            fixture.secrets.failNextDelete();
            assertThat(fixture.cleanupService.drainDeletePending().failed()).isEqualTo(1);
            assertCredentialReadable(reopenedCredentials(), ready.activeCredentialRef());
            assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
            assertThat(sqliteCleanup.findBySecretRef(ready.activeCredentialRef())).isEmpty();
            assertCredentialMissing(reopenedCredentials(), ready.activeCredentialRef());
        } finally {
            fixture.sessions.releaseCloseTransition();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void restore_activation_winning_close_preserves_new_active_and_retries_old_ref_cleanup()
            throws Exception {
        Fixture fixture = fixture();
        String authSessionId = unique("restore-activation-wins-close");
        TrustedDesktopConnection owner = owner(authSessionId);
        String oldActiveRef = fixture.cleanupService.reserveAndWrite(
                authSessionId,
                1,
                "restore-old-access".toCharArray(),
                "restore-old-refresh".toCharArray(),
                "TEST_SEED",
                null);
        OaSessionRecord detached = detached(authSessionId, owner, oldActiveRef);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            fixture.sessions.insert(detached);
            assertThat(fixture.cleanup.consumeReserved(oldActiveRef, authSessionId)).isTrue();
        });
        OaSessionRecord restoring = fixture.persistence.transition(
                authSessionId, detached.generation(), OaSessionPhase.RESTORING);
        OaSessionRecord installing = fixture.persistence.stage(
                authSessionId,
                restoring.generation(),
                owner,
                "restore-new-access".toCharArray(),
                "restore-new-refresh".toCharArray());
        fixture.sessions.blockNextCloseTransition();
        fixture.cleanup.failNextPendingScan();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<OaSessionRecord> close = executor.submit(
                () -> fixture.persistence.detachBeforeCleanup(owner));

        try {
            assertThat(fixture.sessions.closeTransitionStarted().await(5, TimeUnit.SECONDS))
                    .isTrue();
            OaSessionRecord ready = fixture.persistence.activate(
                    authSessionId,
                    installing.generation(),
                    installing.installationId(),
                    owner,
                    "user-restore-race",
                    "tenant-restore-race",
                    "2");
            String newActiveRef = ready.activeCredentialRef();

            fixture.sessions.releaseCloseTransition();
            OaSessionRecord closed = close.get(5, TimeUnit.SECONDS);

            assertThat(closed.phase()).isEqualTo(OaSessionPhase.DETACHED);
            assertThat(closed.activeCredentialRef()).isEqualTo(newActiveRef);
            assertThat(closed.stagedCredentialRef()).isNull();
            assertThat(fixture.sessions.findByAuthSessionId(authSessionId)).contains(closed);
            assertThat(sqliteCleanup.findBySecretRef(oldActiveRef))
                    .get()
                    .extracting(BusinessOaSecretCleanupRecord::state)
                    .isEqualTo(BusinessOaSecretCleanupState.DELETE_PENDING);
            assertThat(sqliteCleanup.findBySecretRef(newActiveRef)).isEmpty();
            assertCredentialReadable(reopenedCredentials(), oldActiveRef);
            assertCredentialReadable(reopenedCredentials(), newActiveRef);

            assertThat(fixture.cleanupService.drainDeletePending().deleted()).isEqualTo(1);
            assertThat(sqliteCleanup.findBySecretRef(oldActiveRef)).isEmpty();
            assertCredentialMissing(reopenedCredentials(), oldActiveRef);
            assertCredentialReadable(reopenedCredentials(), newActiveRef);
        } finally {
            fixture.sessions.releaseCloseTransition();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 创建共享真实资源和只负责观测/故障注入的薄包装。 */
    private Fixture fixture() {
        ObservingSecretStore secrets = new ObservingSecretStore(
                new LocalKeyStoreSecretStore(TEST_KEYSTORE, KEYSTORE_PASSWORD.toCharArray()),
                dataSource);
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);
        TransactionProbe transactionProbe = new TransactionProbe(dataSource);
        ObservingSessionRepository sessions = new ObservingSessionRepository(
                sqliteSessions, transactionProbe);
        FaultInjectingCleanupRepository cleanup = new FaultInjectingCleanupRepository(
                sqliteCleanup, transactionProbe);
        BusinessOaSecretCleanupService cleanupService = new BusinessOaSecretCleanupService(
                cleanup, credentials, transactionManager, CLOCK);
        OaSessionPersistenceService persistence = new OaSessionPersistenceService(
                sessions, cleanup, cleanupService, transactionManager, CLOCK);
        return new Fixture(
                sessions, cleanup, cleanupService, secrets, credentials, persistence,
                transactionProbe);
    }

    /** 建立带旧 active 的 INSTALLING 快照，用于 activation 与 recovery 场景。 */
    private InstallingFixture prepareInstalling(Fixture fixture, String authSessionId) {
        TrustedDesktopConnection owner = owner(authSessionId);
        String oldActiveRef = fixture.cleanupService.reserveAndWrite(
                authSessionId, 1,
                "old-access".toCharArray(), "old-refresh".toCharArray(),
                "TEST_SEED", null);
        OaSessionRecord ready = OaSessionRecord.ready(
                authSessionId, owner.desktopInstanceId(), owner.desktopSessionId(), oldActiveRef, NOW);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            fixture.sessions.insert(ready);
            assertThat(fixture.cleanup.consumeReserved(oldActiveRef, authSessionId)).isTrue();
        });
        OaSessionRecord installing = fixture.persistence.stage(
                authSessionId, ready.generation(), owner,
                "new-access".toCharArray(), "new-refresh".toCharArray());
        return new InstallingFixture(owner, installing, oldActiveRef);
    }

    private BusinessOaSecretCleanupRecord cleanupRecord(String authSessionId) {
        return sqliteCleanup.listByState(BusinessOaSecretCleanupState.DELETE_PENDING).stream()
                .filter(record -> record.authSessionId().equals(authSessionId))
                .findFirst()
                .orElseThrow();
    }

    private static OaSessionRecord authenticating(
            String authSessionId,
            TrustedDesktopConnection owner) {
        return new OaSessionRecord(
                authSessionId, owner.desktopInstanceId(), owner.desktopSessionId(),
                null, null, null, OaSessionPhase.AUTHENTICATING, 1,
                null, null, 0, null, null, null, null, NOW,
                null, null, null, 0, null);
    }

    private static OaSessionRecord detached(
            String authSessionId,
            TrustedDesktopConnection owner,
            String activeCredentialRef) {
        return new OaSessionRecord(
                authSessionId,
                owner.desktopInstanceId(),
                owner.desktopSessionId(),
                "user-old", "tenant-old", "2",
                OaSessionPhase.DETACHED,
                1,
                activeCredentialRef,
                null,
                1,
                null,
                NOW,
                NOW,
                null,
                NOW);
    }

    private static TrustedDesktopConnection owner(String authSessionId) {
        return new TrustedDesktopConnection(
                "reservation-" + authSessionId,
                "desktop-" + authSessionId,
                "session-" + authSessionId,
                "ws-" + authSessionId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static void assertCredentialReadable(
            OaSessionCredentialStore credentials,
            String secretRef) {
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(secretRef)) {
            assertThat(material).isNotNull();
        }
    }

    private static void assertCredentialMissing(
            OaSessionCredentialStore credentials,
            String secretRef) {
        try (OaSessionCredentialStore.CredentialMaterial material = credentials.load(secretRef)) {
            assertThat(material).isNull();
        }
    }

    private static OaSessionCredentialStore reopenedCredentials() {
        char[] password = KEYSTORE_PASSWORD.toCharArray();
        try {
            return new OaSessionCredentialStore(
                    new LocalKeyStoreSecretStore(TEST_KEYSTORE, password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private record Fixture(
            ObservingSessionRepository sessions,
            FaultInjectingCleanupRepository cleanup,
            BusinessOaSecretCleanupService cleanupService,
            ObservingSecretStore secrets,
            OaSessionCredentialStore credentials,
            OaSessionPersistenceService persistence,
            TransactionProbe transactionProbe) {
    }

    private record InstallingFixture(
            TrustedDesktopConnection owner,
            OaSessionRecord record,
            String oldActiveRef) {
    }

    /** 断言真实 SQLite session CAS 位于显式事务内，并统计每次恢复的 CAS 数量。 */
    private static final class ObservingSessionRepository implements OaSessionRepository {
        private final OaSessionRepository delegate;
        private final TransactionProbe transactionProbe;
        private final AtomicInteger exactCasCalls = new AtomicInteger();
        private final AtomicBoolean exactCasObservedInsideTransaction = new AtomicBoolean();
        private final AtomicBoolean blockNextCloseTransition = new AtomicBoolean();
        private final CountDownLatch closeTransitionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCloseTransition = new CountDownLatch(1);

        private ObservingSessionRepository(
                OaSessionRepository delegate,
                TransactionProbe transactionProbe) {
            this.delegate = delegate;
            this.transactionProbe = transactionProbe;
        }

        @Override
        public Optional<OaSessionRecord> findByAuthSessionId(String authSessionId) {
            return delegate.findByAuthSessionId(authSessionId);
        }

        @Override
        public Optional<OaSessionRecord> findByDesktopSession(
                String desktopInstanceId,
                String desktopSessionId) {
            return delegate.findByDesktopSession(desktopInstanceId, desktopSessionId);
        }

        @Override
        public boolean existsCredentialReference(String secretRef) {
            return delegate.existsCredentialReference(secretRef);
        }

        @Override
        public OaSessionRecord insert(OaSessionRecord record) {
            return delegate.insert(record);
        }

        @Override
        public OaSessionRecord update(OaSessionRecord record) {
            return delegate.update(record);
        }

        @Override
        public boolean compareAndSwapGeneration(
                String authSessionId,
                long expectedGeneration,
                OaSessionRecord record) {
            return delegate.compareAndSwapGeneration(authSessionId, expectedGeneration, record);
        }

        @Override
        public boolean compareAndSwapExact(OaSessionRecord expected, OaSessionRecord next) {
            exactCasCalls.incrementAndGet();
            boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            exactCasObservedInsideTransaction.compareAndSet(false, transactionActive);
            assertThat(transactionActive).isTrue();
            transactionProbe.observeSession();
            if (expected.phase() == OaSessionPhase.INSTALLING
                    && (next.phase() == OaSessionPhase.SIGNED_OUT
                    || next.phase() == OaSessionPhase.DETACHED)
                    && blockNextCloseTransition.compareAndSet(true, false)) {
                closeTransitionStarted.countDown();
                try {
                    if (!releaseCloseTransition.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("close transition barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("close transition interrupted", interrupted);
                }
            }
            return delegate.compareAndSwapExact(expected, next);
        }

        @Override
        public List<OaSessionRecord> listRecoverable() {
            return delegate.listRecoverable();
        }

        private int exactCasCalls() {
            return exactCasCalls.get();
        }

        private void resetExactCasCalls() {
            exactCasCalls.set(0);
        }

        private boolean exactCasObservedInsideTransaction() {
            return exactCasObservedInsideTransaction.get();
        }

        private void blockNextCloseTransition() {
            blockNextCloseTransition.set(true);
        }

        private CountDownLatch closeTransitionStarted() {
            return closeTransitionStarted;
        }

        private void releaseCloseTransition() {
            releaseCloseTransition.countDown();
        }
    }

    /** 在真实 cleanup 写入之后抛错，从而证明 SQLite 能回滚两个 repository 的变化。 */
    private static final class FaultInjectingCleanupRepository
            implements BusinessOaSecretCleanupRepository {
        private final BusinessOaSecretCleanupRepository delegate;
        private final TransactionProbe transactionProbe;
        private final AtomicBoolean failAfterConsume = new AtomicBoolean();
        private final AtomicBoolean failAfterUpsert = new AtomicBoolean();
        private final AtomicBoolean failNextPendingScan = new AtomicBoolean();
        private final AtomicBoolean consumeObservedInsideTransaction = new AtomicBoolean();
        private final AtomicBoolean upsertObservedInsideTransaction = new AtomicBoolean();

        private FaultInjectingCleanupRepository(
                BusinessOaSecretCleanupRepository delegate,
                TransactionProbe transactionProbe) {
            this.delegate = delegate;
            this.transactionProbe = transactionProbe;
        }

        @Override
        public BusinessOaSecretCleanupRecord upsertReserved(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            return delegate.upsertReserved(secretRef, authSessionId, reasonCode, operationId, now);
        }

        @Override
        public boolean consumeReserved(String secretRef, String authSessionId) {
            boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            consumeObservedInsideTransaction.compareAndSet(false, transactionActive);
            transactionProbe.observeCleanup();
            boolean consumed = delegate.consumeReserved(secretRef, authSessionId);
            if (failAfterConsume.compareAndSet(true, false)) {
                throw new IllegalStateException("injected cleanup consume failure");
            }
            return consumed;
        }

        @Override
        public BusinessOaSecretCleanupRecord upsertDeletePending(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            boolean transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            upsertObservedInsideTransaction.compareAndSet(false, transactionActive);
            transactionProbe.observeCleanup();
            BusinessOaSecretCleanupRecord record = delegate.upsertDeletePending(
                    secretRef, authSessionId, reasonCode, operationId, now);
            if (failAfterUpsert.compareAndSet(true, false)) {
                throw new IllegalStateException("injected cleanup journal failure");
            }
            return record;
        }

        @Override
        public Optional<BusinessOaSecretCleanupRecord> findBySecretRef(String secretRef) {
            return delegate.findBySecretRef(secretRef);
        }

        @Override
        public boolean markDeletePending(
                String secretRef,
                String reasonCode,
                String operationId,
                Instant now) {
            return delegate.markDeletePending(secretRef, reasonCode, operationId, now);
        }

        @Override
        public boolean markReservedDeletePending(
                String secretRef,
                String authSessionId,
                String reasonCode,
                String operationId,
                Instant now) {
            return delegate.markReservedDeletePending(
                    secretRef, authSessionId, reasonCode, operationId, now);
        }

        @Override
        public boolean recordDeleteFailure(String secretRef, String resultCode, Instant attemptedAt) {
            return delegate.recordDeleteFailure(secretRef, resultCode, attemptedAt);
        }

        @Override
        public List<BusinessOaSecretCleanupRecord> listByState(BusinessOaSecretCleanupState state) {
            return delegate.listByState(state);
        }

        @Override
        public List<BusinessOaSecretCleanupRecord> listDeletePendingBatch(int limit) {
            if (failNextPendingScan.compareAndSet(true, false)) {
                throw new IllegalStateException("injected pending scan failure");
            }
            return delegate.listDeletePendingBatch(limit);
        }

        @Override
        public boolean existsByAuthSessionId(String authSessionId) {
            return delegate.existsByAuthSessionId(authSessionId);
        }

        @Override
        public boolean deleteTombstone(String secretRef) {
            return delegate.deleteTombstone(secretRef);
        }

        private void failAfterConsume() {
            failAfterConsume.set(true);
        }

        private void failAfterUpsertDeletePending() {
            failAfterUpsert.set(true);
        }

        private void failNextPendingScan() {
            failNextPendingScan.set(true);
        }

        private boolean consumeObservedInsideTransaction() {
            return consumeObservedInsideTransaction.get();
        }

        private boolean upsertObservedInsideTransaction() {
            return upsertObservedInsideTransaction.get();
        }
    }

    /** 包装真实 JCEKS，只在入口处断言没有持有 SQLite 事务并支持一次删除失败。 */
    private static final class ObservingSecretStore implements SecretStore {
        private final SecretStore delegate;
        private final AtomicInteger remainingDeleteFailures = new AtomicInteger();
        private final AtomicBoolean writeObservedOutsideTransaction = new AtomicBoolean();
        private final AtomicBoolean deleteObservedOutsideTransaction = new AtomicBoolean();
        private final AtomicInteger deleteCalls = new AtomicInteger();

        private final DataSource dataSource;

        private ObservingSecretStore(SecretStore delegate, DataSource dataSource) {
            this.delegate = delegate;
            this.dataSource = dataSource;
        }

        @Override
        public String allocateRef(String namespace) {
            assertNoTransaction();
            return delegate.allocateRef(namespace);
        }

        @Override
        public void saveCharsAtRef(String secretRef, char[] secretChars) {
            assertNoTransaction();
            writeObservedOutsideTransaction.set(true);
            delegate.saveCharsAtRef(secretRef, secretChars);
        }

        @Override
        public List<String> listRefs(String namespacePrefix) {
            assertNoTransaction();
            return delegate.listRefs(namespacePrefix);
        }

        @Override
        public String save(String namespace, String secretPlainText) {
            assertNoTransaction();
            return delegate.save(namespace, secretPlainText);
        }

        @Override
        public String saveChars(String namespace, char[] secretChars) {
            assertNoTransaction();
            return delegate.saveChars(namespace, secretChars);
        }

        @Override
        public Optional<String> load(String secretRef) {
            assertNoTransaction();
            return delegate.load(secretRef);
        }

        @Override
        public Optional<char[]> loadChars(String secretRef) {
            assertNoTransaction();
            return delegate.loadChars(secretRef);
        }

        @Override
        public void delete(String secretRef) {
            assertNoTransaction();
            deleteCalls.incrementAndGet();
            deleteObservedOutsideTransaction.set(true);
            if (remainingDeleteFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("injected JCEKS delete failure");
            }
            delegate.delete(secretRef);
        }

        private void failNextDelete() {
            failDeletes(1);
        }

        private void failDeletes(int count) {
            remainingDeleteFailures.set(count);
        }

        private int deleteCalls() {
            return deleteCalls.get();
        }

        private boolean writeObservedOutsideTransaction() {
            return writeObservedOutsideTransaction.get();
        }

        private boolean deleteObservedOutsideTransaction() {
            return deleteObservedOutsideTransaction.get();
        }

        private void assertNoTransaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        }
    }

    /** 记录两个 repository 在同一业务操作内实际绑定的 JDBC ConnectionHolder。 */
    private static final class TransactionProbe {
        private final DataSource dataSource;
        private final AtomicReference<Object> sessionResource = new AtomicReference<>();
        private final AtomicReference<Object> cleanupResource = new AtomicReference<>();

        private TransactionProbe(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        private void observeSession() {
            sessionResource.compareAndSet(null, currentResource());
        }

        private void observeCleanup() {
            cleanupResource.compareAndSet(null, currentResource());
        }

        private Object currentResource() {
            Object resource = TransactionSynchronizationManager.getResource(dataSource);
            assertThat(resource).isInstanceOf(ConnectionHolder.class);
            return resource;
        }

        private void assertSessionAndCleanupShareConnection() {
            assertThat(sessionResource.get()).isNotNull();
            assertThat(cleanupResource.get()).isSameAs(sessionResource.get());
        }

        private void reset() {
            sessionResource.set(null);
            cleanupResource.set(null);
        }
    }
}
