package com.wzx.babiq.server.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real local-WebSocket coverage for server-owned OA login, logout and identity replacement. */
@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessOaAuthenticatedWebSocketIT {
    private static final String DESKTOP_TOKEN = "T".repeat(43);
    private static final Path RUNTIME = Path.of(
            "target", "business-oa-authenticated-ws-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");
    private static final Task17BusinessGatewayHarness.Account FIRST =
            new Task17BusinessGatewayHarness.Account(
                    "13800138000", "1001", "2002", 2,
                    "Alpha Firm", "Alice", "access-alpha", "refresh-alpha");
    private static final Task17BusinessGatewayHarness.Account SECOND =
            new Task17BusinessGatewayHarness.Account(
                    "second@example.test", "1002", "3003", 2,
                    "Beta Firm", "Bob", "access-beta", "refresh-beta");
    private static final Task17BusinessGatewayHarness.FakeOaServer OA =
            Task17BusinessGatewayHarness.FakeOaServer.start(FIRST, SECOND);

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
        registry.add("babiq.persistence.database-path", () -> RUNTIME.resolve("data/business.db").toString());
        registry.add("babiq.secrets.keystore-path", () -> RUNTIME.resolve("secrets/business.jceks").toString());
        registry.add("babiq.secrets.keystore-password", () -> "task17-authenticated-ws-password");
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
    private OaSessionCredentialStore credentials;

    @Autowired
    private BusinessDesktopConnectionRegistry connections;

    @Autowired
    private ApplicationIdentityRegistry identities;

    @Autowired
    private ApplicationCatalogRegistry catalogs;

    @Autowired
    private ApplicationPageContextRegistry contexts;

    @AfterAll
    static void cleanup() {
        OA.close();
        Task17BusinessGatewayHarness.deleteTree(RUNTIME);
    }

    @Test
    void explicitLogoutRevokesFirstIdentityBeforeDifferentIdentityCanBecomeReadyOnSameSocket() throws Exception {
        String desktopInstanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        try (Task17BusinessGatewayHarness.RealWebSocketRpcSession rpc =
                     Task17BusinessGatewayHarness.RealWebSocketRpcSession.connect(
                             json, port, DESKTOP_TOKEN, desktopInstanceId, desktopSessionId)) {
            JsonNode firstReady = login(rpc, FIRST, "Password8");
            var firstRecord = sessions.findByDesktopSession(desktopInstanceId, desktopSessionId).orElseThrow();
            String firstCredentialRef = firstRecord.activeCredentialRef();
            long firstGeneration = firstRecord.generation();
            var connection = connections.findByDesktopSessionId(desktopSessionId).orElseThrow();

            assertThat(firstReady.path("state").asText()).isEqualTo("READY");
            assertThat(firstReady.path("userId").asText()).isEqualTo(FIRST.userId());
            assertThat(firstReady.path("tenantId").asText()).isEqualTo(FIRST.tenantId());
            assertThat(identities.current(connection)).get().satisfies(identity ->
                    assertThat(identity.userId()).isEqualTo(FIRST.userId()));

            JsonNode signedOut = rpc.result("business/auth/logout", json.createObjectNode());

            assertThat(signedOut.path("state").asText()).isEqualTo("SIGNED_OUT");
            assertThat(sessions.findByDesktopSession(desktopInstanceId, desktopSessionId)).get()
                    .satisfies(record -> {
                        assertThat(record.phase().name()).isEqualTo("SIGNED_OUT");
                        assertThat(record.activeCredentialRef()).isNull();
                    });
            assertThat(credentials.load(firstCredentialRef)).isNull();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(contexts.current(connection)).isEmpty();

            JsonNode secondReady = login(rpc, SECOND, "Password9");

            assertThat(secondReady.path("state").asText()).isEqualTo("READY");
            assertThat(secondReady.path("userId").asText()).isEqualTo(SECOND.userId());
            assertThat(secondReady.path("tenantId").asText()).isEqualTo(SECOND.tenantId());
            assertThat(secondReady.path("identityEpoch").asLong())
                    .isGreaterThan(firstReady.path("identityEpoch").asLong());
            assertThat(sessions.findByDesktopSession(desktopInstanceId, desktopSessionId)).get()
                    .satisfies(record -> {
                        assertThat(record.phase().name()).isEqualTo("READY");
                        assertThat(record.generation()).isGreaterThan(firstGeneration);
                        assertThat(record.userId()).isEqualTo(SECOND.userId());
                        assertThat(record.tenantId()).isEqualTo(SECOND.tenantId());
                    });
            assertThat(identities.current(connection)).get().satisfies(identity -> {
                assertThat(identity.userId()).isEqualTo(SECOND.userId());
                assertThat(identity.tenantId()).isEqualTo(SECOND.tenantId());
            });
            assertThat(credentials.listBusinessOaRefs()).doesNotContain(firstCredentialRef);
        }

        assertThat(OA.requestsTo("/law-api/system/auth/logout")).singleElement()
                .satisfies(request -> {
                    assertThat(request.method()).isEqualTo("POST");
                    assertThat(request.header("X-Platform-Type")).isEqualTo("pc");
                    assertThat(request.header("Authorization")).isEqualTo("Bearer " + FIRST.accessToken());
                    assertThat(request.header("tenant-id")).isEqualTo(FIRST.tenantId());
                });
        assertThat(OA.requestsTo("/law-api/system/auth/get-permission-info"))
                .extracting(
                        request -> request.header("Authorization"),
                        request -> request.header("tenant-id"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Bearer " + FIRST.accessToken(), FIRST.tenantId()),
                        org.assertj.core.groups.Tuple.tuple("Bearer " + SECOND.accessToken(), SECOND.tenantId()));
    }

    private JsonNode login(
            Task17BusinessGatewayHarness.RealWebSocketRpcSession rpc,
            Task17BusinessGatewayHarness.Account account,
            String password) throws Exception {
        JsonNode candidates = rpc.result(
                "business/auth/tenant-candidates",
                json.createObjectNode().put("account", account.account()));
        String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
        assertThat(candidateId).isNotBlank();
        return rpc.result(
                "business/auth/login",
                json.createObjectNode()
                        .put("account", account.account())
                        .put("candidateId", candidateId)
                        .put("password", password));
    }
}
