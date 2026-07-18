package com.wzx.babiq.server.application;

import com.wzx.babiq.server.BaBiQApplication;
import com.wzx.babiq.server.agent.team.TeamMemoryProperties;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.config.BusinessBackendInstanceLock;
import com.wzx.babiq.server.application.config.BusinessDesktopModeProperties;
import com.wzx.babiq.server.application.protocol.ApplicationIdentityMessage;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.memory.LongTermMemoryProperties;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.recovery.TurnRecoveryService;
import com.wzx.babiq.server.settings.SecretStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock("logback-root")
class BusinessProfileParallelIsolationIT {

    @Test
    void commonAndBusinessContextsKeepAllRuntimeStateIndependentWhileBothAreOpen() throws Exception {
        Path root = Path.of("target", "business-parallel-isolation-it-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Path commonRoot = root.resolve("common");
        Path businessRoot = root.resolve("business");
        RuntimeLayout common = RuntimeLayout.common(commonRoot);
        RuntimeLayout business = RuntimeLayout.business(businessRoot);
        Files.createDirectories(businessRoot);
        Files.writeString(business.token(), "P".repeat(43), StandardCharsets.US_ASCII);

        try (ConfigurableApplicationContext commonContext = startCommon(common);
             BusinessBackendInstanceLock commonLock = BusinessBackendInstanceLock.acquire(common.lock());
             ConfigurableApplicationContext businessContext = startBusiness(business)) {
            assertRuntimePaths(commonContext, common);
            assertRuntimePaths(businessContext, business);
            assertThat(common.database()).isNotEqualTo(business.database());
            assertThat(common.keyStore()).isNotEqualTo(business.keyStore());
            assertThat(common.log()).isNotEqualTo(business.log());
            assertThat(common.memory()).isNotEqualTo(business.memory());
            assertThat(common.teams()).isNotEqualTo(business.teams());

            assertDataAndRecoveryIsolation(commonContext, businessContext, common, business);
            assertSecretIsolation(commonContext, businessContext, common, business);
            assertProcessLockIsolation(businessContext, commonLock, common, business);
            assertBusinessRegistryIsolation(commonContext, businessContext);
        }
    }

    private ConfigurableApplicationContext startCommon(RuntimeLayout layout) {
        return application("common-parallel-test").run(
                "--babiq.persistence.database-path=" + layout.database(),
                "--babiq.secrets.keystore-path=" + layout.keyStore(),
                "--logging.file.name=" + layout.log(),
                "--babiq.memory.long-term.root-dir=" + layout.memory(),
                "--babiq.team.root-dir=" + layout.teams());
    }

    private ConfigurableApplicationContext startBusiness(RuntimeLayout layout) {
        return application("business-desktop").run(
                "--babiq.business.runtime-dir=" + layout.root(),
                "--babiq.persistence.database-path=" + layout.database(),
                "--babiq.secrets.keystore-path=" + layout.keyStore(),
                "--logging.file.name=" + layout.log(),
                "--babiq.memory.long-term.root-dir=" + layout.memory(),
                "--babiq.team.root-dir=" + layout.teams(),
                "--babiq.business.backend-lock-path=" + layout.lock(),
                "--babiq.business.session-token-file=" + layout.token());
    }

    private SpringApplicationBuilder application(String profile) {
        return new SpringApplicationBuilder(BaBiQApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profile)
                .registerShutdownHook(false)
                .logStartupInfo(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "babiq.memory.long-term.enabled=false",
                        "babiq.memory.long-term.generate-enabled=false",
                        "babiq.memory.long-term.read-enabled=false",
                        "babiq.memory.long-term.phase1-on-startup=false");
    }

    private void assertRuntimePaths(ConfigurableApplicationContext context, RuntimeLayout layout) throws Exception {
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains(layout.database().toString());
        }
        assertThat(context.getBean(LongTermMemoryProperties.class).rootDir().toAbsolutePath().normalize())
                .isEqualTo(layout.memory());
        assertThat(context.getBean(TeamMemoryProperties.class).rootDir().toAbsolutePath().normalize())
                .isEqualTo(layout.teams());
        assertThat(Path.of(context.getEnvironment().getRequiredProperty("logging.file.name"))
                .toAbsolutePath().normalize()).isEqualTo(layout.log());
    }

    private void assertDataAndRecoveryIsolation(
            ConfigurableApplicationContext commonContext,
            ConfigurableApplicationContext businessContext,
            RuntimeLayout common,
            RuntimeLayout business) {
        String suffix = UUID.randomUUID().toString();
        String commonThreadId = "common-thread-" + suffix;
        String commonTurnId = "common-turn-" + suffix;
        String businessThreadId = "business-thread-" + suffix;
        String businessTurnId = "business-turn-" + suffix;
        Instant now = Instant.now();

        createRunningTurn(commonContext, commonThreadId, commonTurnId, common.root(), now);
        createRunningTurn(businessContext, businessThreadId, businessTurnId, business.root(), now);

        TurnPersistenceService commonTurns = commonContext.getBean(TurnPersistenceService.class);
        TurnPersistenceService businessTurns = businessContext.getBean(TurnPersistenceService.class);
        TurnRecoveryService commonRecovery = commonContext.getBean(TurnRecoveryService.class);
        TurnRecoveryService businessRecovery = businessContext.getBean(TurnRecoveryService.class);

        assertThat(commonTurns.findTurn(businessTurnId)).isEmpty();
        assertThat(businessTurns.findTurn(commonTurnId)).isEmpty();
        assertThat(commonRecovery).isNotSameAs(businessRecovery);

        assertThat(commonRecovery.recoverAbandonedState().interruptedTurns()).isEqualTo(1);
        assertThat(commonTurns.findTurn(commonTurnId).orElseThrow().getStatus()).isEqualTo("INTERRUPTED");
        assertThat(businessTurns.findTurn(businessTurnId).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(businessRecovery.lastReport().interruptedTurns()).isZero();

        assertThat(businessRecovery.recoverAbandonedState().interruptedTurns()).isEqualTo(1);
        assertThat(businessTurns.findTurn(businessTurnId).orElseThrow().getStatus()).isEqualTo("INTERRUPTED");
    }

    private void createRunningTurn(
            ConfigurableApplicationContext context,
            String threadId,
            String turnId,
            Path cwd,
            Instant now) {
        context.getBean(ConversationRepository.class).createThread(
                threadId, "parallel isolation", cwd.toString(), "provider", "model",
                "READ_ONLY", "NEVER", now);
        context.getBean(TurnPersistenceService.class).saveTurn(TurnRecord.started(
                turnId, threadId, "RUNNING", "isolation", cwd.toString(),
                "provider", "model", "READ_ONLY", "NEVER", now));
    }

    private void assertSecretIsolation(
            ConfigurableApplicationContext commonContext,
            ConfigurableApplicationContext businessContext,
            RuntimeLayout common,
            RuntimeLayout business) {
        SecretStore commonSecrets = commonContext.getBean(SecretStore.class);
        SecretStore businessSecrets = businessContext.getBean(SecretStore.class);
        String commonRef = commonSecrets.save("parallel.common", "common-secret");
        String businessRef = businessSecrets.save("parallel.business", "business-secret");

        assertThat(commonSecrets).isNotSameAs(businessSecrets);
        assertThat(commonSecrets.load(commonRef)).contains("common-secret");
        assertThat(businessSecrets.load(businessRef)).contains("business-secret");
        assertThat(commonSecrets.load(businessRef)).isEmpty();
        assertThat(businessSecrets.load(commonRef)).isEmpty();
        assertThat(Files.isRegularFile(common.keyStore())).isTrue();
        assertThat(Files.isRegularFile(business.keyStore())).isTrue();
    }

    private void assertProcessLockIsolation(
            ConfigurableApplicationContext businessContext,
            BusinessBackendInstanceLock commonLock,
            RuntimeLayout common,
            RuntimeLayout business) {
        BusinessBackendInstanceLock businessLock = businessContext.getBean(BusinessBackendInstanceLock.class);

        assertThat(commonLock.isHeld()).isTrue();
        assertThat(businessLock.isHeld()).isTrue();
        assertThat(commonLock.lockPath()).isEqualTo(common.lock());
        assertThat(businessLock.lockPath()).isEqualTo(business.lock());
        assertThatThrownBy(() -> BusinessBackendInstanceLock.acquire(common.lock()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BusinessBackendInstanceLock.acquire(business.lock()))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertBusinessRegistryIsolation(
            ConfigurableApplicationContext commonContext,
            ConfigurableApplicationContext businessContext) {
        assertThat(commonContext.getBeansOfType(BusinessDesktopConnectionRegistry.class)).isEmpty();
        assertThat(commonContext.getBeansOfType(ApplicationIdentityRegistry.class)).isEmpty();

        BusinessDesktopConnectionRegistry connections =
                businessContext.getBean(BusinessDesktopConnectionRegistry.class);
        ApplicationIdentityRegistry identities = businessContext.getBean(ApplicationIdentityRegistry.class);
        String instanceId = "51111111-1111-4111-8111-111111111111";
        String sessionId = "52222222-2222-4222-8222-222222222222";
        String reservation = connections.reserve(instanceId, sessionId);
        var connection = connections.finalizeReservation(reservation, instanceId, sessionId, "business-ws");
        identities.bind(connection, new ApplicationIdentityMessage(
                "1.0", instanceId, sessionId, "auth-1", 1, 1,
                "2026-07-18T00:00:00Z", "user-1", "tenant-1", "platform-1", true,
                Set.of("lawyer"), Set.of("case:read")));

        assertThat(connections.findByDesktopSessionId(sessionId)).contains(connection);
        assertThat(identities.current(connection)).isPresent();
    }

    private record RuntimeLayout(
            Path root,
            Path database,
            Path keyStore,
            Path log,
            Path memory,
            Path teams,
            Path lock,
            Path token) {

        private RuntimeLayout {
            root = root.toAbsolutePath().normalize();
            database = database.toAbsolutePath().normalize();
            keyStore = keyStore.toAbsolutePath().normalize();
            log = log.toAbsolutePath().normalize();
            memory = memory.toAbsolutePath().normalize();
            teams = teams.toAbsolutePath().normalize();
            lock = lock.toAbsolutePath().normalize();
            token = token.toAbsolutePath().normalize();
        }

        static RuntimeLayout common(Path root) {
            return new RuntimeLayout(root, root.resolve("data/babiq.db"),
                    root.resolve("secrets/babiq.jceks"), root.resolve("logs/backend.log"),
                    root.resolve("memories"), root.resolve("teams"), root.resolve("instance.lock"),
                    root.resolve("unused-token"));
        }

        static RuntimeLayout business(Path root) {
            return new RuntimeLayout(root, root.resolve("data/babiq-business.db"),
                    root.resolve("secrets/business-agent.jceks"), root.resolve("logs/backend.log"),
                    root.resolve("memory"), root.resolve("teams"), root.resolve("instance.lock"),
                    root.resolve("session-token"));
        }
    }
}
