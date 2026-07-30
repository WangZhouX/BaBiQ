package com.wzx.babiq.server.business;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.api.JsonRpcDispatcher;
import com.wzx.babiq.server.api.JsonRpcLogSupport;
import com.wzx.babiq.server.api.JsonRpcMessage;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.application.auth.TrustedDesktopConnection;
import com.wzx.babiq.server.application.config.BusinessBackendInstanceLock;
import com.wzx.babiq.server.business.api.dto.BusinessAuthDtos;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import com.wzx.babiq.server.business.oa.session.ReadyOaSessionLease;
import com.wzx.babiq.server.business.oa.session.DurableOaSessionFixture;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.OaSessionRecord;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import com.wzx.babiq.server.business.upload.BusinessAttachmentPrepareProtocolHandler;
import com.wzx.babiq.server.business.upload.BusinessAttachmentRemoteUploader;
import com.wzx.babiq.server.business.upload.BusinessAttachmentTicketService;
import com.wzx.babiq.server.business.workbench.BusinessWorkbenchDataSanitizer;
import com.wzx.babiq.server.settings.LocalKeyStoreSecretStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

/**
 * Task17 password/token canary audit.
 *
 * <p>The test deliberately keeps the password canary in the synthetic auth frame only. OA
 * tokens are generated at the server boundary and must not cross the RPC, diagnostic, DTO or
 * SQLite boundaries. The JCEKS file is the one expected persistence boundary for token material.</p>
 */
@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ResourceLock("logback-root")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessOaSecretLeakAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD_CANARY = uniquePasswordCanary();
    private static final String ACCESS_TOKEN_CANARY = uniqueTokenCanary("real-access");
    private static final String REFRESH_TOKEN_CANARY = uniqueTokenCanary("real-refresh");
    private static final String TICKET_CANARY = uniqueTokenCanary("upload-ticket");
    private static final String FILE_NAME_CANARY =
            "task17-private-" + UUID.randomUUID().toString().replace("-", "") + ".png";
    private static final String FILE_PATH_CANARY =
            "C:\\private\\" + UUID.randomUUID().toString().replace("-", "") + "\\" + FILE_NAME_CANARY;
    private static final String SHA256_CANARY =
            UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
    private static final String OA_ERROR_CANARY = uniqueTokenCanary("oa-error-body");
    private static final String[] ALL_CANARIES = {
            PASSWORD_CANARY, ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY,
            TICKET_CANARY, FILE_NAME_CANARY, FILE_PATH_CANARY, SHA256_CANARY, OA_ERROR_CANARY
    };
    private static final String[] NON_PASSWORD_CANARIES = {
            ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY,
            TICKET_CANARY, FILE_NAME_CANARY, FILE_PATH_CANARY, SHA256_CANARY, OA_ERROR_CANARY
    };
    private static final String DESKTOP_TOKEN =
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final Path RUNTIME = Path.of(
            "target", "business-oa-secret-audit-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");
    private static final Path DATABASE = RUNTIME.resolve("data/business.db");
    private static final Path KEY_STORE = RUNTIME.resolve("secrets/business.jceks");
    private static final Task17BusinessGatewayHarness.Account ACCOUNT =
            new Task17BusinessGatewayHarness.Account(
                    "audit@example.test", "1701", "2701", 2,
                    "Audit Firm", "Audit User", ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY);
    private static final Task17BusinessGatewayHarness.FakeOaServer OA =
            Task17BusinessGatewayHarness.FakeOaServer.start(ACCOUNT);

    static {
        try {
            Files.createDirectories(RUNTIME);
            Files.writeString(TOKEN_FILE, DESKTOP_TOKEN, StandardCharsets.US_ASCII);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
        registry.add("babiq.business.backend-lock-path", () -> RUNTIME.resolve("instance.lock").toString());
        registry.add("babiq.business.attachment-clipboard-root",
                () -> RUNTIME.resolve("attachments/clipboard").toString());
        registry.add("babiq.persistence.database-path", DATABASE::toString);
        registry.add("babiq.secrets.keystore-path", KEY_STORE::toString);
        registry.add("babiq.secrets.keystore-password", () -> "task17-real-canary-password");
        registry.add("babiq.memory.long-term.enabled", () -> "false");
        registry.add("babiq.memory.long-term.generate-enabled", () -> "false");
        registry.add("babiq.memory.long-term.read-enabled", () -> "false");
        registry.add("babiq.memory.long-term.phase1-on-startup", () -> "false");
        registry.add("babiq.memory.long-term.root-dir", () -> RUNTIME.resolve("memory").toString());
        registry.add("babiq.team.root-dir", () -> RUNTIME.resolve("teams").toString());
        registry.add("logging.file.name", () -> RUNTIME.resolve("logs/backend.log").toString());
        registry.add("huitai.oa.base-url", OA::baseUrl);
        registry.add("huitai.oa.allow-private-http", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OaSessionRepository sessions;

    @Autowired
    private OaSessionCredentialStore serverCredentials;

    @Autowired
    private BusinessBackendInstanceLock backendInstanceLock;

    @TempDir
    Path tempDir;

    @AfterAll
    void releaseRuntimeLockAuditAllArtifactsAndCleanup() throws Exception {
        try {
            // Windows denies reads of the live instance-lock file. Release that controlled writer,
            // then audit every runtime file without ignoring or weakening any I/O failure.
            backendInstanceLock.close();
            assertDatabaseDoesNotContainCanaries(DATABASE, ALL_CANARIES);
            assertFileTreeDoesNotContainCanaries(RUNTIME, ALL_CANARIES);
        } finally {
            OA.close();
            Task17BusinessGatewayHarness.deleteTree(RUNTIME);
        }
    }

    @Test
    void realLoginKeepsPasswordAndOaTokenCanariesInsideTheirControlledBoundaries() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        List<String> outboundFrames = new ArrayList<>();
        List<String> inboundFrames = new ArrayList<>();
        String authSessionId = null;
        String credentialRef = null;
        try {
            String desktopInstanceId = UUID.randomUUID().toString();
            String desktopSessionId = UUID.randomUUID().toString();
            try (Task17BusinessGatewayHarness.RealWebSocketRpcSession rpc =
                         Task17BusinessGatewayHarness.RealWebSocketRpcSession.connect(
                                 json, port, DESKTOP_TOKEN, desktopInstanceId, desktopSessionId)) {
                JsonNode candidates = rpc.result(
                        "business/auth/tenant-candidates",
                        json.createObjectNode().put("account", ACCOUNT.account()));
                String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
                JsonNode ready = rpc.result(
                        "business/auth/login",
                        json.createObjectNode()
                                .put("account", ACCOUNT.account())
                                .put("candidateId", candidateId)
                                .put("password", PASSWORD_CANARY));

                assertThat(ready.path("state").asText()).isEqualTo("READY");
                authSessionId = ready.path("authSessionId").asText();
                OaSessionRecord readyRecord = sessions.findByAuthSessionId(authSessionId).orElseThrow();
                try (OaSessionCredentialStore.CredentialMaterial material =
                             serverCredentials.load(readyRecord.activeCredentialRef())) {
                    assertThat(material).isNotNull();
                    assertThat(java.util.Arrays.equals(
                            material.accessToken(), ACCESS_TOKEN_CANARY.toCharArray()))
                            .as("JCEKS must contain the server-owned access credential")
                            .isTrue();
                    assertThat(java.util.Arrays.equals(
                            material.refreshToken(), REFRESH_TOKEN_CANARY.toCharArray()))
                            .as("JCEKS must contain the server-owned refresh credential")
                            .isTrue();
                }
                outboundFrames.addAll(rpc.outboundFrames());
                inboundFrames.addAll(rpc.inboundFrames());
            }

            String capturedAuthSessionId = authSessionId;
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(sessions.findByAuthSessionId(capturedAuthSessionId)).get()
                            .satisfies(record -> assertThat(record.phase().name()).isEqualTo("DETACHED")));

            try (Task17BusinessGatewayHarness.RealWebSocketRpcSession rpc =
                         Task17BusinessGatewayHarness.RealWebSocketRpcSession.connect(
                                 json, port, DESKTOP_TOKEN, desktopInstanceId, desktopSessionId)) {
                JsonNode detached = rpc.result("business/auth/session/get", json.createObjectNode());
                String attachHandle = detached.path("attachHandle").asText();
                assertThat(detached.path("state").asText()).isEqualTo("DETACHED");
                assertThat(attachHandle).isNotBlank();
                JsonNode attached = rpc.result(
                        "business/auth/session/attach",
                        json.createObjectNode().put("attachHandle", attachHandle));
                assertThat(attached.path("state").asText()).isEqualTo("READY");

                OaSessionRecord attachedRecord = sessions.findByAuthSessionId(authSessionId).orElseThrow();
                credentialRef = attachedRecord.activeCredentialRef();
                try (OaSessionCredentialStore.CredentialMaterial material =
                             serverCredentials.load(credentialRef)) {
                    assertThat(material).isNotNull();
                    assertThat(java.util.Arrays.equals(
                            material.accessToken(), ACCESS_TOKEN_CANARY.toCharArray()))
                            .as("refreshed JCEKS access credential must stay server-owned")
                            .isTrue();
                    assertThat(java.util.Arrays.equals(
                            material.refreshToken(), REFRESH_TOKEN_CANARY.toCharArray()))
                            .as("refreshed JCEKS refresh credential must stay server-owned")
                            .isTrue();
                }

                OA.respondNextError(
                        "/law-api/system/auth/logout",
                        200,
                        500,
                        "remote error " + OA_ERROR_CANARY + " "
                                + ACCESS_TOKEN_CANARY + " " + REFRESH_TOKEN_CANARY);
                JsonNode signedOut = rpc.result("business/auth/logout", json.createObjectNode());
                assertThat(signedOut.path("state").asText()).isEqualTo("SIGNED_OUT");
                outboundFrames.addAll(rpc.outboundFrames());
                inboundFrames.addAll(rpc.inboundFrames());
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        String outbound = String.join("\n", outboundFrames);
        assertThat(count(outbound, PASSWORD_CANARY))
                .as("raw password must occur exactly once in the controlled login frame")
                .isEqualTo(1);
        assertAbsent("desktop outbound RPC", outbound, NON_PASSWORD_CANARIES);
        assertAbsent("desktop inbound RPC", String.join("\n", inboundFrames), ALL_CANARIES);

        String logs = appender.list.stream().map(BusinessOaSecretLeakAuditTest::eventText)
                .collect(Collectors.joining("\n"));
        assertAbsent("backend logs", logs, ALL_CANARIES);

        List<Task17BusinessGatewayHarness.RecordedRequest> httpRequests = OA.requests();
        assertThat(httpRequests).extracting(Task17BusinessGatewayHarness.RecordedRequest::path)
                .containsExactly(
                        "/law-api/system/auth/get-users-by-mobile",
                        "/law-api/system/auth/login",
                        "/law-api/system/auth/get-permission-info",
                        "/law-api/system/auth/refresh-token",
                        "/law-api/system/auth/get-permission-info",
                        "/law-api/system/auth/logout");
        assertAbsent("backend to OA request outside controlled credential fields",
                httpRequests.stream().map(BusinessOaSecretLeakAuditTest::requestTextOutsideControlledSecrets)
                        .collect(Collectors.joining("\n")),
                ALL_CANARIES);
        List<Task17BusinessGatewayHarness.RecordedRequest> authorized = httpRequests.stream()
                .filter(request -> request.header("Authorization") != null)
                .toList();
        assertThat(authorized).extracting(Task17BusinessGatewayHarness.RecordedRequest::path)
                .containsExactly(
                        "/law-api/system/auth/get-permission-info",
                        "/law-api/system/auth/get-permission-info",
                        "/law-api/system/auth/logout");
        authorized.forEach(request -> assertThat(count(
                        request.header("Authorization"), ACCESS_TOKEN_CANARY))
                .as("controlled OA Authorization must carry one access token")
                .isEqualTo(1));
        authorized.forEach(request -> assertThat(request.header("Authorization"))
                .as("controlled OA Authorization must contain only the expected access token")
                .isEqualTo("Bearer " + ACCESS_TOKEN_CANARY));
        assertThat(authorized.stream().mapToInt(request ->
                count(request.header("Authorization"), REFRESH_TOKEN_CANARY)).sum()).isZero();
        assertThat(httpRequests.stream()
                .filter(request -> request.path().equals("/law-api/system/auth/refresh-token")))
                .singleElement()
                .satisfies(request -> {
                    assertThat(request.header("Authorization")).isNull();
                    assertThat(request.body())
                            .as("controlled refresh form must contain only the expected refresh token")
                            .isEqualTo("refreshToken=" + REFRESH_TOKEN_CANARY);
                    assertThat(count(request.body(), REFRESH_TOKEN_CANARY))
                            .as("controlled refresh form must carry one refresh token")
                            .isEqualTo(1);
                    assertThat(count(request.body(), ACCESS_TOKEN_CANARY)).isZero();
                });

        assertThat(authSessionId).isNotBlank();
        assertThat(credentialRef).isNotBlank();
        assertThat(serverCredentials.load(credentialRef)).isNull();
        String signedOutAuthSessionId = authSessionId;
        assertThat(serverCredentials.listBusinessOaRefs())
                .noneMatch(secretRef -> secretRef.contains(signedOutAuthSessionId));
        assertThat(sessions.findByAuthSessionId(authSessionId)).get().satisfies(record -> {
            assertThat(record.phase().name()).isEqualTo("SIGNED_OUT");
            assertThat(record.activeCredentialRef()).isNull();
            assertThat(record.stagedCredentialRef()).isNull();
        });

        assertDatabaseDoesNotContainCanaries(DATABASE, ALL_CANARIES);
    }

    @Test
    void password_canary_is_present_once_in_the_controlled_auth_frame_but_not_in_log_summary_or_session_payload()
            throws Exception {
        String passwordCanary = uniquePasswordCanary();
        ObjectNode params = JSON.createObjectNode()
                .put("account", "audit-account")
                .put("candidateId", "opaque-candidate")
                .put("password", passwordCanary);
        JsonRpcMessage.Request request =
                new JsonRpcMessage.Request("2.0", 1L, "business/auth/login", params);
        String frame = JSON.writeValueAsString(request);

        assertThat(count(frame, passwordCanary)).isEqualTo(1);
        assertThat(request.toString()).doesNotContain(passwordCanary);
        assertThat(JsonRpcLogSupport.paramsSummary("business/auth/login", params))
                .doesNotContain(passwordCanary);

        BusinessAuthDtos.Session session = new BusinessAuthDtos.Session(
                "auth-session", "READY", 3L, "user-1", "Lawyer", "tenant-1", "Firm",
                "2", java.util.Set.of("lawyer"), java.util.Set.of("law:read"),
                "audit-account", false, false);
        String response = JSON.writeValueAsString(JsonRpcMessage.Response.ok(1L, session));
        assertThat(response).doesNotContain(passwordCanary);
    }

    @Test
    void attachment_ticket_filename_path_sha_and_oa_error_canaries_are_redacted_from_diagnostics() {
        ObjectNode params = JSON.createObjectNode()
                .put("operation", "SCHEDULE_CREATE")
                .put("clientOperationId", "task17-audit-operation")
                .put("scope", "PERSONAL")
                .put("typeId", "schedule-type")
                .put("parentRelationType", "CASE")
                .put("parentResourceId", "case-1")
                .put("formRevision", 1);
        params.putArray("files").addObject()
                .put("fileName", FILE_NAME_CANARY)
                .put("localPath", FILE_PATH_CANARY)
                .put("sizeBytes", 8)
                .put("mediaType", "image/png")
                .put("sha256", SHA256_CANARY);

        assertAbsent(
                "attachment prepare log summary",
                JsonRpcLogSupport.paramsSummary("business/attachments/upload/prepare", params),
                TICKET_CANARY, FILE_NAME_CANARY, FILE_PATH_CANARY, SHA256_CANARY, OA_ERROR_CANARY);

        var declaration = new BusinessAttachmentTicketService.FileDeclaration(
                FILE_NAME_CANARY, 8, "image/png", SHA256_CANARY);
        var staged = new BusinessAttachmentRemoteUploader.StagedFile(
                FILE_NAME_CANARY, "image/png", 8, SHA256_CANARY, Path.of(FILE_PATH_CANARY));
        var prepared = new BusinessAttachmentTicketService.PreparedBatch(
                "batch-canary", TICKET_CANARY, Instant.now());
        var response = new BusinessAttachmentPrepareProtocolHandler.PrepareResponse(
                "batch-canary", TICKET_CANARY, Instant.now().toString(), 1, 1);
        assertAbsent(
                "attachment DTO diagnostics",
                declaration + "\n" + staged + "\n" + prepared + "\n" + response,
                TICKET_CANARY, FILE_NAME_CANARY, FILE_PATH_CANARY, SHA256_CANARY, OA_ERROR_CANARY);

        RuntimeException remoteBodyFailure = new IllegalStateException(
                "OA response failed: " + OA_ERROR_CANARY);
        var mapped = com.wzx.babiq.server.business.api.BusinessRpcErrorMapper.map(remoteBodyFailure);
        var sanitizedRemoteFailure = OaRemoteRequestException.networkFailure(true);
        assertAbsent(
                "mapped OA error diagnostics",
                mapped.message() + "\n" + mapped.businessCode() + "\n" + sanitizedRemoteFailure,
                TICKET_CANARY, FILE_NAME_CANARY, FILE_PATH_CANARY, SHA256_CANARY, OA_ERROR_CANARY);
    }

    @Test
    void token_canary_stays_inside_jceks_and_sqlite_stores_only_an_opaque_reference() throws Exception {
        String accessTokenCanary = uniqueTokenCanary("access");
        String refreshTokenCanary = uniqueTokenCanary("refresh");
        Path keyStorePath = tempDir.resolve("oa-secrets.jceks");
        LocalKeyStoreSecretStore secrets = LocalKeyStoreSecretStore.forBusinessProfile(
                keyStorePath, "task17-audit-keystore-password".toCharArray());
        OaSessionCredentialStore credentials = new OaSessionCredentialStore(secrets);

        String ref = DurableOaSessionFixture.seedCredential(credentials,"audit-session", 1,
                accessTokenCanary.toCharArray(), refreshTokenCanary.toCharArray());
        assertThat(ref).doesNotContain(accessTokenCanary, refreshTokenCanary);

        byte[] keyStoreBytes = Files.readAllBytes(keyStorePath);
        String keyStoreText = new String(keyStoreBytes, StandardCharsets.ISO_8859_1);
        String keyStoreBase64 = Base64.getEncoder().encodeToString(keyStoreBytes);
        assertThat(keyStoreText).doesNotContain(accessTokenCanary, refreshTokenCanary);
        assertThat(keyStoreBase64).doesNotContain(accessTokenCanary, refreshTokenCanary);

        Path database = tempDir.resolve("oa-session-index.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            createSessionIndex(connection);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO bq_business_oa_sessions
                    (auth_session_id, desktop_instance_id, desktop_session_id, user_id, tenant_id,
                     platform_id, phase, generation, active_credential_ref, staged_credential_ref,
                     credential_version, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, "auth-session");
                insert.setString(2, "desktop-instance");
                insert.setString(3, "desktop-session");
                insert.setString(4, "user-1");
                insert.setString(5, "tenant-1");
                insert.setString(6, "2");
                insert.setString(7, "READY");
                insert.setLong(8, 1L);
                insert.setString(9, ref);
                insert.setString(10, null);
                insert.setInt(11, 1);
                insert.setString(12, Instant.now().toString());
                insert.executeUpdate();
            }
            try (ResultSet result = connection.createStatement().executeQuery(
                    "SELECT * FROM bq_business_oa_sessions WHERE auth_session_id='auth-session'")) {
                assertThat(result.next()).isTrue();
                String row = rowText(result);
                assertThat(row).doesNotContain(accessTokenCanary, refreshTokenCanary);
                assertThat(row).contains(ref);
            }
        }
        byte[] databaseBytes = Files.readAllBytes(database);
        String databaseText = new String(databaseBytes, StandardCharsets.ISO_8859_1);
        assertThat(databaseText).doesNotContain(accessTokenCanary, refreshTokenCanary);
        assertThat(Base64.getEncoder().encodeToString(databaseBytes))
                .doesNotContain(accessTokenCanary, refreshTokenCanary);

        OaSessionCredentialStore.CredentialMaterial material = credentials.load(ref);
        assertThat(material).isNotNull();
        char[] loadedAccessToken = material.accessToken();
        char[] loadedRefreshToken = material.refreshToken();
        assertThat(loadedAccessToken).isEqualTo(accessTokenCanary.toCharArray());
        assertThat(loadedRefreshToken).isEqualTo(refreshTokenCanary.toCharArray());
        material.close();
        assertThat(loadedAccessToken).containsOnly('\0');
        assertThat(loadedRefreshToken).containsOnly('\0');
    }

    @Test
    void token_canary_is_not_exposed_by_auth_dtos_leases_or_domain_to_strings() throws Exception {
        String accessTokenCanary = uniqueTokenCanary("access");
        String refreshTokenCanary = uniqueTokenCanary("refresh");
        OaAuthDtos.OaCredential credential = new OaAuthDtos.OaCredential(
                accessTokenCanary, refreshTokenCanary, "user-1", 123L);
        assertThat(credential.toString()).doesNotContain(accessTokenCanary, refreshTokenCanary);

        ReadyOaSessionLease lease = new ReadyOaSessionLease(
                "auth-session", "desktop-instance", "desktop-session", "ws-session",
                "user-1", "tenant-1", "2", 2L, "keystore://business-oa-ref", 1, Instant.now());
        TrustedDesktopConnection connection = new TrustedDesktopConnection(
                "reservation", "desktop-instance", "desktop-session", "ws-session");
        OaSessionRecord record = OaSessionRecord.ready(
                "auth-session", "desktop-instance", "desktop-session", "keystore://business-oa-ref", Instant.now());

        assertThat(lease.toString()).doesNotContain(accessTokenCanary, refreshTokenCanary);
        assertThat(connection.toString()).doesNotContain(accessTokenCanary, refreshTokenCanary);
        assertThat(record.toString()).doesNotContain(accessTokenCanary, refreshTokenCanary);
        assertThat(new JsonRpcMessage.ErrorResponse(
                "2.0", 1L,
                new JsonRpcMessage.ErrorResponse.Error(-32040, "Remote service unavailable", null))
                .toString()).doesNotContain(accessTokenCanary, refreshTokenCanary);

        Object sanitizedWorkbench = BusinessWorkbenchDataSanitizer.sanitize("profile", Map.of(
                "nickname", "Audit",
                "accessToken", accessTokenCanary,
                "refreshToken", refreshTokenCanary,
                "nested", Map.of("token", accessTokenCanary)));
        assertThat(String.valueOf(sanitizedWorkbench))
                .doesNotContain(accessTokenCanary, refreshTokenCanary);

        var mapped = com.wzx.babiq.server.business.api.BusinessRpcErrorMapper.map(
                OaRemoteRequestException.networkFailure(false));
        assertThat(mapped.message()).doesNotContain(accessTokenCanary, refreshTokenCanary);
    }

    @Test
    void unexpected_rpc_exception_does_not_echo_token_canary_into_logs_or_error_response() {
        String tokenCanary = uniqueTokenCanary("exception");
        Logger logger = (Logger) LoggerFactory.getLogger(JsonRpcDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JsonRpcMethodHandler handler = new JsonRpcMethodHandler() {
                @Override
                public String method() {
                    return "business/audit/failure";
                }

                @Override
                public Object handle(JsonNode params, WebSocketSession session) {
                    throw new IllegalStateException(tokenCanary);
                }
            };
            JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(handler), JSON);
            JsonRpcMessage.ErrorResponse response = (JsonRpcMessage.ErrorResponse) dispatcher.dispatch(
                    new JsonRpcMessage.Request("2.0", 7L, "business/audit/failure", Map.of()),
                    mock(WebSocketSession.class));

            assertThat(response.error().code()).isEqualTo(JsonRpcErrorCode.SERVER_ERROR.code());
            assertThat(response.error().message()).isEqualTo("Internal server error");
            String logs = appender.list.stream().map(BusinessOaSecretLeakAuditTest::eventText)
                    .collect(Collectors.joining("\n"));
            assertThat(logs).doesNotContain(tokenCanary);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mapped_remote_errors_and_oa_error_bodies_do_not_retain_token_canary() throws Exception {
        RuntimeException remoteBodyFailure = new IllegalStateException(
                "OA response failed: " + OA_ERROR_CANARY + " trace=remote-secret");

        var mapped = com.wzx.babiq.server.business.api.BusinessRpcErrorMapper.map(remoteBodyFailure);
        assertThat(mapped.message()).doesNotContain(OA_ERROR_CANARY);
        assertThat(mapped.businessCode()).doesNotContain(OA_ERROR_CANARY);

        JsonRpcMessage.ErrorResponse response = JsonRpcMessage.ErrorResponse.of(
                3L, JsonRpcErrorCode.SERVER_ERROR, "Internal server error", null);
        assertThat(JSON.writeValueAsString(response)).doesNotContain(OA_ERROR_CANARY);
    }

    @Test
    void task17_harness_diagnostic_strings_redact_controlled_credentials() {
        Task17BusinessGatewayHarness.RecordedRequest request =
                new Task17BusinessGatewayHarness.RecordedRequest(
                        "POST",
                        "/law-api/system/auth/refresh-token",
                        null,
                        "refreshToken=" + REFRESH_TOKEN_CANARY,
                        Map.of("Authorization", "Bearer " + ACCESS_TOKEN_CANARY));
        Task17BusinessGatewayHarness.RefreshCredential refresh =
                new Task17BusinessGatewayHarness.RefreshCredential(
                        ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY, ACCOUNT.userId());
        Task17BusinessGatewayHarness.FailureResponse failure =
                new Task17BusinessGatewayHarness.FailureResponse(
                        500, ACCESS_TOKEN_CANARY + " " + REFRESH_TOKEN_CANARY);

        assertAbsent("fake OA account diagnostics", ACCOUNT.toString(),
                ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY);
        assertAbsent("fake OA request diagnostics", request.toString(),
                ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY);
        assertAbsent("fake OA refresh diagnostics", refresh.toString(),
                ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY);
        assertAbsent("fake OA failure diagnostics", failure.toString(),
                ACCESS_TOKEN_CANARY, REFRESH_TOKEN_CANARY);
    }

    private static String uniquePasswordCanary() {
        // OaPasswordEncoder accepts only 8-16 ASCII alpha-numeric characters.
        return "P7" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
    }

    private static String uniqueTokenCanary(String label) {
        return "TASK17_" + label.toUpperCase() + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String requestTextOutsideControlledSecrets(
            Task17BusinessGatewayHarness.RecordedRequest request) {
        String headers = request.headers().entrySet().stream()
                .filter(entry -> !entry.getKey().equalsIgnoreCase("Authorization"))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        String body = request.body();
        if ("/law-api/system/auth/refresh-token".equals(request.path()) && body != null) {
            body = java.util.Arrays.stream(body.split("&", -1))
                    .map(field -> {
                        String[] parts = field.split("=", 2);
                        String name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                        return "refreshToken".equals(name)
                                ? parts[0] + "=[CONTROLLED]"
                                : field;
                    })
                    .collect(Collectors.joining("&"));
        }
        return request.method() + "\n" + request.path() + "\n" + request.query()
                + "\n" + body + "\n" + headers;
    }

    private static void assertAbsent(String surface, String value, String... canaries) {
        String text = value == null ? "" : value;
        for (String canary : canaries) {
            assertThat(count(text, canary))
                    .as(surface + " must not contain a sensitive canary")
                    .isZero();
        }
    }

    private static void assertDatabaseDoesNotContainCanaries(Path database, String... canaries)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            List<String> tables = new ArrayList<>();
            try (ResultSet result = connection.createStatement().executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                while (result.next()) tables.add(result.getString(1));
            }
            for (String table : tables) {
                String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
                try (ResultSet rows = connection.createStatement().executeQuery("SELECT * FROM " + quoted)) {
                    int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        for (int column = 1; column <= columns; column++) {
                            assertAbsent("SQLite table " + table, rows.getString(column), canaries);
                        }
                    }
                }
            }
        }
    }

    private static void assertFileTreeDoesNotContainCanaries(Path root, String... canaries)
            throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                assertAbsent("runtime artifact path", root.relativize(path).toString(), canaries);
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(path);
                for (String canary : canaries) {
                    assertThat(count(bytes, canary.getBytes(StandardCharsets.US_ASCII)))
                            .as("runtime artifact must not contain a sensitive canary: "
                                    + root.relativize(path))
                            .isZero();
                }
            }
        }
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static int count(byte[] value, byte[] needle) {
        int matches = 0;
        for (int offset = 0; offset <= value.length - needle.length; offset++) {
            boolean equal = true;
            for (int index = 0; index < needle.length; index++) {
                if (value[offset + index] != needle[index]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                matches++;
                offset += needle.length - 1;
            }
        }
        return matches;
    }

    private static String eventText(ILoggingEvent event) {
        String throwable = event.getThrowableProxy() == null
                ? ""
                : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy());
        return event.getFormattedMessage() + "\n" + throwable;
    }

    private static void createSessionIndex(Connection connection) throws Exception {
        connection.createStatement().execute("""
                CREATE TABLE bq_business_oa_sessions (
                    auth_session_id TEXT PRIMARY KEY,
                    desktop_instance_id TEXT NOT NULL,
                    desktop_session_id TEXT NOT NULL,
                    user_id TEXT,
                    tenant_id TEXT,
                    platform_id TEXT,
                    phase TEXT NOT NULL,
                    generation INTEGER NOT NULL,
                    active_credential_ref TEXT,
                    staged_credential_ref TEXT,
                    credential_version INTEGER NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
    }

    private static String rowText(ResultSet result) throws Exception {
        StringBuilder value = new StringBuilder();
        for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
            value.append('|').append(result.getString(index));
        }
        return value.toString();
    }
}
