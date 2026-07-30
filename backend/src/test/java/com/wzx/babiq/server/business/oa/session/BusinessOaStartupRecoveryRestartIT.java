package com.wzx.babiq.server.business.oa.session;

import com.wzx.babiq.server.BaBiQApplication;
import com.wzx.babiq.server.recovery.StartupRecoveryCoordinator;
import com.wzx.babiq.server.settings.LocalKeyStoreSecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Proves that native OA cleanup is driven by a real second business-profile process context. */
@ResourceLock("logback-root")
class BusinessOaStartupRecoveryRestartIT {
    private static final String KEYSTORE_PASSWORD = "oa-startup-restart-test-password";
    private static final String SESSION_TOKEN = "A".repeat(43);

    @Test
    void second_business_profile_startup_recovers_orphan_and_preserves_ready_credential()
            throws Exception {
        RuntimeLayout layout = RuntimeLayout.create();
        SeededResources seeded;

        try (ConfigurableApplicationContext first = start(layout)) {
            assertRecoveryCompletedAfterOa(first);
            BusinessOaSecretCleanupService cleanupService =
                    first.getBean(BusinessOaSecretCleanupService.class);
            BusinessOaSecretCleanupRepository cleanupRepository =
                    first.getBean(BusinessOaSecretCleanupRepository.class);
            OaSessionRepository sessions = first.getBean(OaSessionRepository.class);
            OaSessionCredentialStore credentials = first.getBean(OaSessionCredentialStore.class);

            String orphanAuthSessionId = "restart-orphan-" + UUID.randomUUID();
            String orphanRef = cleanupService.reserveAndWrite(
                    orphanAuthSessionId,
                    1,
                    "restart-orphan-access".toCharArray(),
                    "restart-orphan-refresh".toCharArray(),
                    "TEST_RESTART_ORPHAN",
                    null);
            String readyAuthSessionId = "restart-ready-" + UUID.randomUUID();
            String readyRef = cleanupService.reserveAndWrite(
                    readyAuthSessionId,
                    1,
                    "restart-ready-access".toCharArray(),
                    "restart-ready-refresh".toCharArray(),
                    "TEST_RESTART_READY",
                    null);
            OaSessionRecord ready = OaSessionRecord.ready(
                    readyAuthSessionId,
                    "desktop-" + readyAuthSessionId,
                    "session-" + readyAuthSessionId,
                    readyRef,
                    Instant.parse("2026-07-28T08:00:00Z"));
            sessions.insert(ready);

            assertThat(cleanupRepository.findBySecretRef(orphanRef).orElseThrow().state())
                    .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
            assertThat(cleanupRepository.findBySecretRef(readyRef).orElseThrow().state())
                    .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
            assertThat(sessions.existsCredentialReference(orphanRef)).isFalse();
            assertThat(sessions.existsCredentialReference(readyRef)).isTrue();
            assertCredentialReadable(credentials, orphanRef);
            assertCredentialReadable(credentials, readyRef);
            seeded = new SeededResources(orphanRef, readyRef, ready);
        }

        try (ConfigurableApplicationContext restarted = start(layout)) {
            assertRecoveryCompletedAfterOa(restarted);
            BusinessOaSecretCleanupRepository cleanupRepository =
                    restarted.getBean(BusinessOaSecretCleanupRepository.class);
            OaSessionRepository sessions = restarted.getBean(OaSessionRepository.class);
            OaSessionCredentialStore credentials = restarted.getBean(OaSessionCredentialStore.class);

            assertThat(cleanupRepository.findBySecretRef(seeded.orphanRef())).isEmpty();
            assertCredentialMissing(credentials, seeded.orphanRef());
            assertThat(cleanupRepository.findBySecretRef(seeded.readyRef()).orElseThrow().state())
                    .isEqualTo(BusinessOaSecretCleanupState.RESERVED);
            assertThat(sessions.findByAuthSessionId(seeded.ready().authSessionId()))
                    .contains(seeded.ready());
            assertCredentialReadable(credentials, seeded.readyRef());
        }

        OaSessionCredentialStore reopened = reopenedCredentials(layout.keyStore());
        assertCredentialMissing(reopened, seeded.orphanRef());
        assertCredentialReadable(reopened, seeded.readyRef());
    }

    private ConfigurableApplicationContext start(RuntimeLayout layout) throws Exception {
        Files.createDirectories(layout.root());
        Files.writeString(
                layout.sessionToken(), SESSION_TOKEN, StandardCharsets.US_ASCII);
        return new SpringApplicationBuilder(
                BaBiQApplication.class,
                RecoveryObservationConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("business-desktop")
                .registerShutdownHook(false)
                .logStartupInfo(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "babiq.memory.long-term.enabled=false",
                        "babiq.memory.long-term.generate-enabled=false",
                        "babiq.memory.long-term.read-enabled=false",
                        "babiq.memory.long-term.phase1-on-startup=false")
                .run(
                        "--babiq.business.runtime-dir=" + layout.root(),
                        "--babiq.persistence.database-path=" + layout.database(),
                        "--babiq.secrets.keystore-path=" + layout.keyStore(),
                        "--babiq.secrets.keystore-password=" + KEYSTORE_PASSWORD,
                        "--logging.file.name=" + layout.log(),
                        "--babiq.memory.long-term.root-dir=" + layout.memory(),
                        "--babiq.team.root-dir=" + layout.teams(),
                        "--babiq.business.backend-lock-path=" + layout.lock(),
                        "--babiq.business.session-token-file=" + layout.sessionToken(),
                        "--babiq.business.attachment-clipboard-root=" + layout.clipboard(),
                        "--huitai.oa.base-url=http://127.0.0.1:48080");
    }

    private static void assertRecoveryCompletedAfterOa(ConfigurableApplicationContext context) {
        RecoveryObservation observation = context.getBean(RecoveryObservation.class);
        StartupRecoveryCoordinator coordinator =
                context.getBean(StartupRecoveryCoordinator.class);

        assertThat(observation.calls()).isEqualTo(1);
        assertThat(observation.gateWasClosedBeforeRecovery()).isTrue();
        assertThat(observation.gateWasClosedAfterRecovery()).isTrue();
        assertThat(coordinator.isRecoveryComplete()).isTrue();
    }

    private static OaSessionCredentialStore reopenedCredentials(Path keyStore) {
        char[] password = KEYSTORE_PASSWORD.toCharArray();
        try {
            return new OaSessionCredentialStore(
                    new LocalKeyStoreSecretStore(keyStore, password));
        } finally {
            Arrays.fill(password, '\0');
        }
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

    @TestConfiguration(proxyBeanMethods = false)
    static class RecoveryObservationConfiguration {
        @Bean
        RecoveryObservation recoveryObservation() {
            return new RecoveryObservation();
        }

        @Bean
        @Primary
        BusinessOaSessionRecoveryService observedOaSessionRecoveryService(
                OaSessionRepository repository,
                OaSessionPersistenceService persistence,
                StartupRecoveryCoordinator coordinator,
                RecoveryObservation observation) {
            BusinessOaSessionRecoveryService delegate =
                    new BusinessOaSessionRecoveryService(repository, persistence);
            BusinessOaSessionRecoveryService observed = spy(delegate);
            doAnswer(invocation -> {
                observation.recordBefore(coordinator);
                BusinessOaSessionRecoveryService.RecoveryReport report = delegate.recover();
                observation.recordAfter(coordinator);
                return report;
            }).when(observed).recover();
            return observed;
        }
    }

    static final class RecoveryObservation {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean gateWasClosedBeforeRecovery = new AtomicBoolean();
        private final AtomicBoolean gateWasClosedAfterRecovery = new AtomicBoolean();

        void recordBefore(StartupRecoveryCoordinator coordinator) {
            calls.incrementAndGet();
            gateWasClosedBeforeRecovery.set(!coordinator.isRecoveryComplete());
        }

        void recordAfter(StartupRecoveryCoordinator coordinator) {
            gateWasClosedAfterRecovery.set(!coordinator.isRecoveryComplete());
        }

        int calls() {
            return calls.get();
        }

        boolean gateWasClosedBeforeRecovery() {
            return gateWasClosedBeforeRecovery.get();
        }

        boolean gateWasClosedAfterRecovery() {
            return gateWasClosedAfterRecovery.get();
        }
    }

    private record SeededResources(
            String orphanRef,
            String readyRef,
            OaSessionRecord ready) {
    }

    private record RuntimeLayout(
            Path root,
            Path database,
            Path keyStore,
            Path log,
            Path memory,
            Path teams,
            Path lock,
            Path sessionToken,
            Path clipboard) {
        static RuntimeLayout create() {
            Path root = Path.of(
                            "target",
                            "business-oa-startup-restart-" + UUID.randomUUID())
                    .toAbsolutePath()
                    .normalize();
            return new RuntimeLayout(
                    root,
                    root.resolve("data/business.db"),
                    root.resolve("secrets/business.jceks"),
                    root.resolve("logs/backend.log"),
                    root.resolve("memory"),
                    root.resolve("teams"),
                    root.resolve("instance.lock"),
                    root.resolve("session-token"),
                    root.resolve("attachments/clipboard"));
        }
    }
}
