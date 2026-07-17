package com.wzx.babiq.server.application.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.agent.team.TeamMemoryProperties;
import com.wzx.babiq.server.api.method.TurnCancelHandler;
import com.wzx.babiq.server.api.method.TurnInterruptHandler;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.action.ApplicationMessageSequence;
import com.wzx.babiq.server.application.api.ApplicationActionProtocolHandler;
import com.wzx.babiq.server.application.tool.ApplicationActionTool;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.conversation.repository.TurnRecord;
import com.wzx.babiq.server.memory.LongTermMemoryProperties;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.TurnMapper;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import com.wzx.babiq.server.recovery.TurnRecoveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@ActiveProfiles("business-desktop")
@SpringBootTest
@ResourceLock("logback-root")
class BusinessDesktopProfileIsolationIT {

    private static final Path TEST_RUNTIME = Path.of(
            "target", "business-profile-it-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TEST_TOKEN_FILE = TEST_RUNTIME.resolve("session-token");
    private static final String TEST_TOKEN = "A".repeat(43);

    @DynamicPropertySource
    static void businessRuntime(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(TEST_RUNTIME);
        Files.writeString(TEST_TOKEN_FILE, TEST_TOKEN, StandardCharsets.US_ASCII);
        applyOwnerOnlyPermissions(TEST_TOKEN_FILE);
        registry.add("babiq.business.runtime-dir", TEST_RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TEST_TOKEN_FILE::toString);
    }

    @Autowired
    private BusinessDesktopModeProperties properties;

    @Autowired
    private BusinessDesktopRuntimePaths runtimePaths;

    @Autowired
    private BusinessBackendInstanceLock backendInstanceLock;

    @Autowired
    private LongTermMemoryProperties memoryProperties;

    @Autowired
    private TeamMemoryProperties teamProperties;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private TurnPersistenceService turnPersistenceService;

    @Autowired
    private TurnRecoveryService recoveryService;

    @Autowired
    private TurnMapper turnMapper;

    @Autowired
    private PendingApplicationActions pendingApplicationActions;

    @Autowired
    private TurnCancelHandler turnCancelHandler;

    @Autowired
    private TurnInterruptHandler turnInterruptHandler;

    @Autowired
    private ApplicationMessageSequence applicationMessageSequence;

    @Autowired
    private ApplicationActionProtocolHandler applicationActionProtocolHandler;

    @Autowired
    private ApplicationActionTool applicationActionTool;

    private BusinessDesktopRuntimePaths testLoggingPaths;

    @BeforeEach
    void installOwnedTestLoggingAppender() {
        testLoggingPaths = new BusinessDesktopRuntimePaths(properties);
    }

    @AfterEach
    void closeOwnedTestLoggingAppender() {
        testLoggingPaths.close();
    }

    @Test
    void createsOnlyTheRequiredBusinessDirectoriesAndNeverCreatesTheSessionTokenFile() {
        assertThat(runtimePaths.runtimeDir()).isEqualTo(properties.runtimeDir());
        assertThat(List.of(
                properties.runtimeDir(),
                properties.databasePath().getParent(),
                properties.keyStorePath().getParent(),
                properties.logPath().getParent(),
                properties.memoryRoot(),
                properties.teamRoot(),
                properties.backendLockPath().getParent(),
                properties.sessionTokenFile().getParent()
        )).allSatisfy(path -> assertThat(Files.isDirectory(path)).isTrue());
        assertThat(Files.notExists(properties.sessionTokenFile())).isTrue();
    }

    @Test
    void runtimeDirectoriesAndLockFileAreRestrictedToTheCurrentOwner() throws Exception {
        assertOwnerOnlyAccess(properties.runtimeDir(), true);
        assertOwnerOnlyAccess(properties.databasePath().getParent(), true);
        assertOwnerOnlyAccess(properties.keyStorePath().getParent(), true);
        assertOwnerOnlyAccess(properties.logPath().getParent(), true);
        assertOwnerOnlyAccess(properties.memoryRoot(), true);
        assertOwnerOnlyAccess(properties.teamRoot(), true);
        assertOwnerOnlyAccess(properties.backendLockPath(), false);
    }

    @Test
    void runtimePathsRejectAnExistingReparsePointThatEscapesTheRuntime() throws Exception {
        Path outside = Files.createDirectories(properties.runtimeDir().resolveSibling("junction-outside"));
        Path junction = properties.runtimeDir().resolve("linked-data");
        createDirectoryJunction(junction, outside);
        BusinessDesktopModeProperties linkedProperties = new BusinessDesktopModeProperties(
                true,
                properties.runtimeDir(),
                junction.resolve("business.db"),
                properties.keyStorePath(),
                properties.logPath(),
                properties.memoryRoot(),
                properties.teamRoot(),
                properties.backendLockPath(),
                properties.sessionTokenFile(),
                properties.authenticationRequired(),
                properties.serverAddress(),
                properties.allowedOrigins(),
                properties.maxEnvelopeBytes(),
                properties.maxCatalogPayloadBytes(),
                properties.maxContextPayloadBytes(),
                properties.maxActionInputBytes(),
                properties.maxActionResultBytes(),
                properties.acceptTimeout(),
                properties.previewTimeout(),
                properties.approvalTimeout(),
                properties.executeTimeout(),
                properties.reconciliationGraceTimeout());

        assertThat(Files.readAttributes(junction, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .isOther()).isTrue();
        assertThatThrownBy(() -> new BusinessDesktopRuntimePaths(linkedProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link")
                .hasMessageNotContaining(junction.toString());
    }

    @Test
    void runtimePathsRejectAReparsePointInTheRuntimeAncestorChain() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Path outside = Files.createDirectories(
                properties.runtimeDir().resolveSibling("runtime-ancestor-outside-" + suffix));
        Path junction = properties.runtimeDir().resolveSibling("runtime-ancestor-junction-" + suffix);
        createDirectoryJunction(junction, outside);
        Path linkedRuntime = junction.resolve("nested-runtime");
        BusinessDesktopModeProperties linkedProperties = propertiesForRuntime(linkedRuntime);

        assertThatThrownBy(() -> new BusinessDesktopRuntimePaths(linkedProperties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link")
                .hasMessageNotContaining(junction.toString());
    }

    @Test
    void flywayMemoryTeamsAndLoggingUseOnlyTheBusinessTree() throws Exception {
        Path runtime = properties.runtimeDir();
        assertThat(memoryProperties.rootDir()).isEqualTo(properties.memoryRoot());
        assertThat(teamProperties.rootDir()).isEqualTo(properties.teamRoot());
        assertThat(Path.of(environment.getRequiredProperty("logging.file.name")).toAbsolutePath().normalize())
                .isEqualTo(properties.logPath());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")) {
            assertThat(connection.getMetaData().getURL()).contains(properties.databasePath().toString());
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isPositive();
        }

        String marker = "business-profile-log-probe";
        LoggerFactory.getLogger(BusinessDesktopProfileIsolationIT.class).info(marker);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(Files.isRegularFile(properties.logPath())).isTrue();
            assertThat(Files.readString(properties.logPath(), StandardCharsets.UTF_8)).contains(marker);
        });

        assertThat(List.of(
                properties.databasePath(),
                properties.logPath(),
                memoryProperties.rootDir(),
                teamProperties.rootDir()
        )).allSatisfy(path -> assertThat(path.toAbsolutePath().normalize().startsWith(runtime)).isTrue());
    }

    @Test
    void recoveryChangesOnlyTheBusinessDatabase() throws Exception {
        Path commonDatabase = properties.runtimeDir().resolveSibling(
                "common-babiq-" + UUID.randomUUID()).resolve("babiq.db");
        Files.createDirectories(commonDatabase.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + commonDatabase);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bq_turns (turn_id TEXT PRIMARY KEY, status TEXT NOT NULL)");
            statement.execute("INSERT INTO bq_turns(turn_id, status) VALUES ('common-running', 'RUNNING')");
        }

        String suffix = UUID.randomUUID().toString();
        String threadId = "business-thread-" + suffix;
        String turnId = "business-turn-" + suffix;
        Instant now = Instant.now();
        conversationRepository.createThread(threadId, "business recovery", properties.runtimeDir().toString(),
                "provider", "model", "READ_ONLY", "ON_REQUEST", now);
        turnPersistenceService.saveTurn(TurnRecord.started(
                turnId, threadId, "RUNNING", "recover", properties.runtimeDir().toString(),
                "provider", "model", "READ_ONLY", "ON_REQUEST", now));

        recoveryService.recoverAbandonedState();

        TurnEntity businessTurn = turnMapper.selectOne(Wrappers.<TurnEntity>lambdaQuery()
                .eq(TurnEntity::getTurnId, turnId));
        assertThat(businessTurn.getStatus()).isEqualTo("INTERRUPTED");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + commonDatabase);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT status FROM bq_turns WHERE turn_id = 'common-running'")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("RUNNING");
        }
    }

    @Test
    void springManagedBackendLockIsExclusiveForTheConfiguredBusinessRuntime() {
        assertThat(backendInstanceLock.isHeld()).isTrue();
        assertThat(backendInstanceLock.lockPath()).isEqualTo(properties.backendLockPath());
        assertThatThrownBy(() -> BusinessBackendInstanceLock.acquire(properties.backendLockPath()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void independentlyAcquiredBackendLockReleasesOnClose() {
        Path lockPath = properties.runtimeDir().resolve("secondary-runtime").resolve("instance.lock");

        try (BusinessBackendInstanceLock first = BusinessBackendInstanceLock.acquire(lockPath)) {
            assertThat(first.isHeld()).isTrue();
            assertThatThrownBy(() -> BusinessBackendInstanceLock.acquire(lockPath))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(lockPath.toString());
        }

        try (BusinessBackendInstanceLock reacquired = BusinessBackendInstanceLock.acquire(lockPath)) {
            assertThat(reacquired.isHeld()).isTrue();
        }
    }

    @Test
    void backendLockRejectsAReparsePointInItsPath() throws Exception {
        Path outside = Files.createDirectories(properties.runtimeDir().resolveSibling("lock-junction-outside"));
        Path junction = properties.runtimeDir().resolve("lock-junction");
        createDirectoryJunction(junction, outside);

        assertThatThrownBy(() -> BusinessBackendInstanceLock.acquire(junction.resolve("instance.lock")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link");
    }

    @Test
    void fileLoggingMovesToTheCurrentBusinessRuntimeInsteadOfReusingAnOlderAppender() throws Exception {
        Path secondRuntime = properties.runtimeDir().resolveSibling("second-business-runtime");
        BusinessDesktopModeProperties secondProperties = propertiesForRuntime(secondRuntime);
        String marker = "second-runtime-log-" + UUID.randomUUID();

        try (BusinessDesktopRuntimePaths secondPaths = new BusinessDesktopRuntimePaths(secondProperties)) {
            LoggerFactory.getLogger(BusinessDesktopProfileIsolationIT.class).info(marker);

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(Files.isRegularFile(secondProperties.logPath())).isTrue();
                assertThat(Files.readString(secondProperties.logPath(), StandardCharsets.UTF_8)).contains(marker);
            });
            assertThat(Files.readString(properties.logPath(), StandardCharsets.UTF_8)).doesNotContain(marker);
        }
    }

    @Test
    void fileLoggingUsesRollingOutputAndReleasesItsAppenderOnClose() {
        Path secondRuntime = properties.runtimeDir().resolveSibling("closable-business-runtime");
        BusinessDesktopModeProperties secondProperties = propertiesForRuntime(secondRuntime);
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);

        BusinessDesktopRuntimePaths paths = new BusinessDesktopRuntimePaths(secondProperties);
        assertThat(root.getAppender("BUSINESS_DESKTOP_FILE")).isInstanceOf(RollingFileAppender.class);

        paths.close();

        assertThat(root.getAppender("BUSINESS_DESKTOP_FILE")).isNull();
    }

    @Test
    void businessProfileNeverUsesTheCommonBabiqRuntimePaths() {
        Path commonRoot = Path.of(System.getProperty("user.home"), ".babiq").toAbsolutePath().normalize();

        assertThat(properties.runtimeDir().startsWith(commonRoot)).isFalse();
        assertThat(properties.databasePath()).isNotEqualTo(commonRoot.resolve("babiq.db"));
        assertThat(properties.keyStorePath()).isNotEqualTo(commonRoot.resolve("babiq-secrets.jceks"));
        assertThat(properties.memoryRoot()).isNotEqualTo(commonRoot.resolve("memories"));
        assertThat(properties.teamRoot()).isNotEqualTo(commonRoot.resolve("teams"));
        assertThat(properties.backendLockPath()).isNotEqualTo(commonRoot.resolve("instance.lock"));
    }

    @Test
    void springInjectsPendingApplicationActionsIntoBothTurnCancellationHandlers() {
        assertThat(ReflectionTestUtils.getField(turnCancelHandler, "pendingApplicationActions"))
                .isSameAs(pendingApplicationActions);
        assertThat(ReflectionTestUtils.getField(turnInterruptHandler, "pendingApplicationActions"))
                .isSameAs(pendingApplicationActions);
    }

    @Test
    void springInjectsTheSharedApplicationMessageSequenceIntoTheProtocolHandler() {
        assertThat(ReflectionTestUtils.getField(applicationActionProtocolHandler, "messageSequence"))
                .isSameAs(applicationMessageSequence);
    }

    @Test
    void businessProfileRegistersTheApplicationActionTool() {
        assertThat(applicationActionTool.name()).isEqualTo("application_action");
    }

    private BusinessDesktopModeProperties propertiesForRuntime(Path runtime) {
        Path normalized = runtime.toAbsolutePath().normalize();
        return new BusinessDesktopModeProperties(
                true,
                normalized,
                normalized.resolve("data/babiq-business.db"),
                normalized.resolve("secrets/business-agent.jceks"),
                normalized.resolve("logs/backend.log"),
                normalized.resolve("memory"),
                normalized.resolve("teams"),
                normalized.resolve("instance.lock"),
                normalized.resolve("session-token"),
                true,
                "127.0.0.1",
                "http://127.0.0.1",
                properties.maxEnvelopeBytes(),
                properties.maxCatalogPayloadBytes(),
                properties.maxContextPayloadBytes(),
                properties.maxActionInputBytes(),
                properties.maxActionResultBytes(),
                properties.acceptTimeout(),
                properties.previewTimeout(),
                properties.approvalTimeout(),
                properties.executeTimeout(),
                properties.reconciliationGraceTimeout());
    }

    private static void createDirectoryJunction(Path junction, Path target) throws Exception {
        Process process = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", "-")
                .redirectErrorStream(true)
                .start();
        String command = "New-Item -ItemType Junction -Path '"
                + escapePowerShellLiteral(junction.toString())
                + "' -Target '"
                + escapePowerShellLiteral(target.toString())
                + "' | Out-Null\n";
        process.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static String escapePowerShellLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void assertOwnerOnlyAccess(Path path, boolean directory) throws Exception {
        PosixFileAttributeView posixView = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posixView != null) {
            Set<PosixFilePermission> expected = directory
                    ? Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertThat(Files.getPosixFilePermissions(path)).isEqualTo(expected);
            return;
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assertThat(aclView).as("ACL support for " + path.getFileName()).isNotNull();
        UserPrincipal owner = Files.getOwner(path);
        List<AclEntry> allowedEntries = aclView.getAcl().stream()
                .filter(entry -> entry.type() == AclEntryType.ALLOW)
                .toList();
        assertThat(allowedEntries).isNotEmpty();
        assertThat(allowedEntries)
                .allSatisfy(entry -> assertThat(entry.principal()).isEqualTo(owner));
    }

    private static void applyOwnerOnlyPermissions(Path path) throws Exception {
        PosixFileAttributeView posixView = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posixView != null) {
            Files.setPosixFilePermissions(
                    path,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (aclView == null) {
            return;
        }
        AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path))
                .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                .build();
        aclView.setAcl(List.of(ownerEntry));
    }
}
