package com.wzx.babiq.server.business.oa.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class BusinessOaSessionRecoveryServiceTest {

    @Test
    void recoversEveryInterruptedAuthenticationPhase() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        OaSessionRecord authenticating = record("auth-authenticating", OaSessionPhase.AUTHENTICATING);
        OaSessionRecord restoring = record("auth-restoring", OaSessionPhase.RESTORING);
        OaSessionRecord installing = record("auth-installing", OaSessionPhase.INSTALLING);
        OaSessionRecord revoking = record("auth-revoking", OaSessionPhase.REVOKING);
        when(repository.listRecoverable()).thenReturn(List.of(authenticating, restoring, installing, revoking));
        when(persistence.recoverInstallingBeforeCleanup(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessOaSessionRecoveryService service = new BusinessOaSessionRecoveryService(repository, persistence);

        BusinessOaSessionRecoveryService.RecoveryReport report = service.recover();

        assertThat(report.scanned()).isEqualTo(4);
        assertThat(report.recovered()).isEqualTo(4);
        verify(persistence).recoverInstallingBeforeCleanup(authenticating);
        verify(persistence).recoverInstallingBeforeCleanup(restoring);
        verify(persistence).recoverInstallingBeforeCleanup(installing);
        verify(persistence).recoverInstallingBeforeCleanup(revoking);
        verify(persistence).drainPendingCredentialCleanup();
    }

    @Test
    void isolatesOneStaleCasFailureAndContinuesRecoveringOtherSessions() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        OaSessionRecord failed = record("auth-failed", OaSessionPhase.INSTALLING);
        OaSessionRecord recovered = record("auth-recovered", OaSessionPhase.REVOKING);
        when(repository.listRecoverable()).thenReturn(List.of(failed, recovered));
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == failed) throw new IllegalStateException("stale CAS");
            return invocation.getArgument(0);
        }).when(persistence).recoverInstallingBeforeCleanup(any());

        BusinessOaSessionRecoveryService.RecoveryReport report =
                new BusinessOaSessionRecoveryService(repository, persistence).recover();

        assertThat(report).isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(2, 1, 1));
        verify(persistence).recoverInstallingBeforeCleanup(failed);
        verify(persistence).recoverInstallingBeforeCleanup(recovered);
        verify(persistence).drainPendingCredentialCleanup();
    }

    @Test
    void credentialCleanupHasOneBatchBoundary() {
        OaSessionRepository repository = mock(OaSessionRepository.class);
        OaSessionPersistenceService persistence = mock(OaSessionPersistenceService.class);
        OaSessionRecord first = record("auth-first", OaSessionPhase.INSTALLING);
        OaSessionRecord second = record("auth-second", OaSessionPhase.REVOKING);
        List<String> events = new ArrayList<>();
        when(repository.listRecoverable()).thenReturn(List.of(first, second));
        doAnswer(invocation -> {
            OaSessionRecord candidate = invocation.getArgument(0);
            events.add("recover:" + candidate.authSessionId());
            return candidate;
        }).when(persistence).recoverInstallingBeforeCleanup(any());
        doAnswer(invocation -> {
            events.add("drain");
            throw new IllegalStateException("cleanup drain failed");
        }).when(persistence).drainPendingCredentialCleanup();
        BusinessOaSessionRecoveryService service =
                new BusinessOaSessionRecoveryService(repository, persistence);
        AtomicReference<BusinessOaSessionRecoveryService.RecoveryReport> report =
                new AtomicReference<>();

        assertThatCode(() -> report.set(service.recover()))
                .doesNotThrowAnyException();

        assertThat(report.get())
                .isEqualTo(new BusinessOaSessionRecoveryService.RecoveryReport(2, 2, 0));
        assertThat(events).containsExactly(
                "recover:auth-first", "recover:auth-second", "drain");
        verify(persistence).recoverInstallingBeforeCleanup(first);
        verify(persistence).recoverInstallingBeforeCleanup(second);
        verify(persistence).drainPendingCredentialCleanup();
    }

    private static OaSessionRecord record(String authSessionId, OaSessionPhase phase) {
        return new OaSessionRecord(authSessionId, "desktop-1", "session-1", "user-1", "tenant-1", "2",
                phase, 3, "active-ref", "staged-ref", 1, Instant.now(), null, null, null, Instant.now());
    }
}
