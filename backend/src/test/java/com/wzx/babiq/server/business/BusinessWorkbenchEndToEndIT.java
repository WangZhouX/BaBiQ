package com.wzx.babiq.server.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.BusinessOaSessionRegistry;
import com.wzx.babiq.server.business.workbench.BusinessWorkbenchService;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cross-layer smoke coverage for the server-owned OA boundary.
 *
 * <p>The Spring Boot server, local WebSocket and HTTP OA adapter are real. The OA HTTP server is
 * deterministic and local-only so this test never needs a real account or network access.</p>
 */
@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessWorkbenchEndToEndIT {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    private static final Path RUNTIME = Path.of("target", "business-workbench-e2e-" + UUID.randomUUID())
            .toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");
    private static RecordingOaServer oa;

    @BeforeAll
    static void startFakeOa() throws Exception {
        Files.createDirectories(RUNTIME);
        Files.writeString(TOKEN_FILE, TOKEN, StandardCharsets.US_ASCII);
        oa = RecordingOaServer.start();
    }

    @AfterAll
    static void stopFakeOa() {
        if (oa != null) oa.stop();
        if (!RUNTIME.normalize().startsWith(Path.of("target").toAbsolutePath().normalize())) return;
        try (var paths = Files.walk(RUNTIME)) {
            List<Path> all = paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList();
            all.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            RUNTIME.toFile().deleteOnExit();
        }
    }

    @DynamicPropertySource
    static void businessProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
        registry.add("babiq.persistence.database-path", () -> RUNTIME.resolve("data/business.db").toString());
        registry.add("babiq.secrets.keystore-path", () -> RUNTIME.resolve("secrets/business.jceks").toString());
        registry.add("babiq.secrets.keystore-password", () -> "business-workbench-e2e-password");
        registry.add("huitai.oa.base-url", () -> oa.baseUrl());
        registry.add("huitai.oa.allow-private-http", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OaSessionRepository oaSessionRepository;

    @Autowired
    private OaSessionCredentialStore oaSessionCredentialStore;

    @Autowired
    private BusinessDesktopConnectionRegistry connections;

    @Autowired
    private ApplicationIdentityRegistry identities;

    @Autowired
    private ApplicationCatalogRegistry catalogs;

    @Autowired
    private ApplicationPageContextRegistry pageContexts;

    @Autowired
    private BusinessOaSessionRegistry businessSessions;

    @Autowired
    private BusinessWorkbenchService workbenchService;

    private final List<String> invokedRpcMethods = new CopyOnWriteArrayList<>();

    @Test
    void loginInstallsReadyIdentityAndReadsWorkbenchThroughLocalWebSocket() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            assertThat(Files.notExists(TOKEN_FILE)).isTrue();
            JsonNode denied = exchange(session, inbound, 1, "business/workbench/get", json.createObjectNode());
            assertThat(denied.at("/error/code").asInt()).isEqualTo(-32601);

            JsonNode signedOut = result(exchange(session, inbound, 2,
                    "business/auth/session/get", json.createObjectNode()));
            assertThat(signedOut.path("state").asText()).isEqualTo("SIGNED_OUT");

            JsonNode candidates = result(exchange(session, inbound, 3,
                    "business/auth/tenant-candidates", json.createObjectNode().put("account", "13800138000")));
            String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
            assertThat(candidateId).isNotBlank();

            JsonNode ready = result(exchange(session, inbound, 4, "business/auth/login", json.createObjectNode()
                    .put("account", "13800138000")
                    .put("candidateId", candidateId)
                    .put("password", "Correct123")));
            assertThat(ready.path("state").asText()).isEqualTo("READY");
            assertThat(ready.path("identityEpoch").asLong()).isPositive();
            assertThat(ready.path("canRestore").asBoolean()).isFalse();

            JsonNode workbench = result(exchange(session, inbound, 5, "business/workbench/get",
                    json.createObjectNode().put("month", "2026-07").put("day", "2026-07-27")));
            JsonNode snapshot = workbench.path("snapshot");
            assertThat(List.of("notices", "shortcuts", "summary", "profile", "teams", "schedule"))
                    .allSatisfy(section -> assertThat(snapshot.path(section).path("status").asText())
                            .as(section + ": " + workbench).isEqualTo("OK"));
            assertThat(snapshot.path("issues")).isEmpty();
            assertThat(workbench.path("snapshot").path("shortcuts").path("data").toString())
                    .contains("/case").doesNotContain("remote-shortcut-token");
            assertThat(workbench.path("snapshot").path("profile").path("data").path("nickname").asText())
                    .isEqualTo("Alice");
            assertThat(workbench.toString()).doesNotContain("access-e2e", "refresh-e2e", "remote-password");

            JsonNode navigation = result(exchange(session, inbound, 6,
                    "business/workbench/navigation/get", json.createObjectNode()));
            assertThat(navigation.path("items")).hasSize(1);
            assertThat(navigation.path("items").toString())
                    .contains("WORKBENCH")
                    .doesNotContain("/appointment", "/visit", "/schedule");
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(oa.paths()).contains("/law-api/system/auth/get-users-by-mobile",
                        "/law-api/system/auth/login", "/law-api/system/auth/get-permission-info",
                        "/law-api/lawyer/home-config/list-shortcut", "/law-api/lawyer/law-schedule/list-day"));
        assertThat(oa.authorizedRequests()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void readyWorkbenchPagesAllFourBusinessKindsThroughTheirOaRoutes() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        Map<String, String> expectedPaths = Map.of(
                "CASE", "/law-api/lawyer/home-config/summary/case-handling-page",
                "APPOINTMENT", "/law-api/lawyer/home-config/summary/appointment-page",
                "COUNSELOR_SERVICE", "/law-api/counselor/home-config/summary/counselor-service-page",
                "VISIT", "/law-api/counselor/home-config/summary/visiting-page");
        Map<String, String> filterNames = Map.of(
                "CASE", "status",
                "APPOINTMENT", "consultMode",
                "COUNSELOR_SERVICE", "serviceStatus",
                "VISIT", "visitObj");
        Map<String, Integer> filterValues = Map.of(
                "CASE", 1,
                "APPOINTMENT", 0,
                "COUNSELOR_SERVICE", 0,
                "VISIT", 1);
        Map<String, Long> requestsBefore = expectedPaths.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> oa.pathCount(entry.getValue())));

        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            loginReady(session, inbound, 51);
            long requestId = 53;
            for (String kind : List.of("CASE", "APPOINTMENT", "COUNSELOR_SERVICE", "VISIT")) {
                var params = json.createObjectNode()
                        .put("kind", kind)
                        .put("scope", "PERSONAL")
                        .put("pageNo", 1)
                        .put("pageSize", 10);
                params.putObject("filters").put(filterNames.get(kind), filterValues.get(kind));

                JsonNode page = result(exchange(session, inbound, requestId++,
                        "business/workbench/page/get", params));

                assertThat(page.path("total").asLong()).isEqualTo(1);
                assertThat(page.path("items")).hasSize(1);
                assertThat(page.path("items").path(0).path("id").asText())
                        .isEqualTo(kind.toLowerCase(java.util.Locale.ROOT) + "-1");
                assertThat(page.toString()).doesNotContain("remote-page-token");
            }
        }

        expectedPaths.forEach((kind, path) -> assertThat(
                oa.requestsTo(path).stream().skip(requestsBefore.get(kind)).toList()).singleElement()
                .satisfies(request -> {
                    assertThat(request.method()).isEqualTo("GET");
                    assertThat(request.query()).contains(
                            "pageNo=1", "pageSize=10", "dataType=0",
                            filterNames.get(kind) + "=" + filterValues.get(kind));
                }));
    }

    @Test
    void readyWorkbenchSortAndScheduleCreateEachPerformOneRemoteWrite() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        String createPath = "/law-api/lawyer/law-schedule/create";
        String sortPath = "/law-api/lawyer/home-config/update-sort";
        long createsBefore = oa.pathCount(createPath);
        long sortsBefore = oa.pathCount(sortPath);

        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            loginReady(session, inbound, 71);
            var sort = json.createObjectNode().put("kind", "SHORTCUT").put("expectedRevision", 0);
            sort.putArray("ids").add("shortcut-1");
            JsonNode sorted = result(exchange(session, inbound, 73,
                    "business/workbench/sort/update", sort));
            assertThat(sorted.path("revision").asLong()).isPositive();

            ObjectNode create = scheduleCreateParams(UUID.randomUUID().toString());

            JsonNode created = result(exchange(session, inbound, 74,
                    "business/schedule/create", create));
            JsonNode duplicate = result(exchange(session, inbound, 75,
                    "business/schedule/create", create));

            assertThat(created.path("revision").asLong()).isPositive();
            assertThat(duplicate).isEqualTo(created);
            assertThat(created.toString()).doesNotContain("access-e2e", "refresh-e2e");
        }

        assertThat(oa.pathCount(sortPath) - sortsBefore).isEqualTo(1);
        assertThat(oa.pathCount(createPath) - createsBefore).isEqualTo(1);
        RecordingOaServer.Request sortRequest = oa.requestsTo(sortPath).getLast();
        RecordingOaServer.Request createRequest = oa.requestsTo(createPath).getLast();
        assertThat(sortRequest.method()).isEqualTo("PUT");
        assertThat(json.readTree(sortRequest.body()).path("configType").asInt()).isEqualTo(1);
        assertThat(createRequest.method()).isEqualTo("POST");
        JsonNode remoteCreate = json.readTree(createRequest.body());
        assertThat(remoteCreate.path("schTitle").asText()).isEqualTo("Prepare hearing");
        assertThat(remoteCreate.path("schId").asText()).isEqualTo("type-1");
        assertThat(remoteCreate.toString()).doesNotContain("access-e2e", "refresh-e2e");
    }

    @Test
    void concurrentHttp401ReadsShareOneRefreshFlightThroughRealWebSocket() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        String refreshPath = "/law-api/system/auth/refresh-token";
        String countPath = "/law-api/lawyer/law-schedule/list-count";
        String dayPath = "/law-api/lawyer/law-schedule/list-day";
        long refreshesBefore = oa.pathCount(refreshPath);
        long expiredCountReadsBefore =
                oa.pathCountWithAuthorization(countPath, "Bearer access-e2e");
        long expiredDayReadsBefore =
                oa.pathCountWithAuthorization(dayPath, "Bearer access-e2e");
        Task17BusinessGatewayHarness.RequestBarrier refreshBarrier = null;

        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound);
             ExecutorService caller = Executors.newSingleThreadExecutor()) {
            loginReady(session, inbound, 81);
            oa.expireOriginalScheduleAccess = true;
            refreshBarrier = oa.blockNext(refreshPath);

            CompletableFuture<JsonNode> workbench = CompletableFuture.supplyAsync(() -> {
                try {
                    return result(exchange(session, inbound, 83,
                            "business/workbench/get", json.createObjectNode()));
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, caller);

            refreshBarrier.awaitArrival();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(List.of(
                            oa.pathCountWithAuthorization(countPath, "Bearer access-e2e")
                                    - expiredCountReadsBefore,
                            oa.pathCountWithAuthorization(dayPath, "Bearer access-e2e")
                                    - expiredDayReadsBefore))
                            .containsExactly(1L, 1L));
            refreshBarrier.release();

            assertThat(workbench.get(8, TimeUnit.SECONDS)
                    .path("snapshot").path("schedule").path("status").asText())
                    .isEqualTo("OK");
        } finally {
            oa.expireOriginalScheduleAccess = false;
            if (refreshBarrier != null) refreshBarrier.release();
        }

        assertThat(oa.pathCount(refreshPath) - refreshesBefore).isEqualTo(1);
    }

    @Test
    void explicitLogoutBypassesBlockedReadAndLateResponseIsRejectedAsStale() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        Task17BusinessGatewayHarness.RequestBarrier readBarrier =
                oa.blockNext("/law-api/system/user/home-info");
        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound);
             ExecutorService caller = Executors.newSingleThreadExecutor()) {
            loginReady(session, inbound, 71);
            CompletableFuture<JsonNode> blockedRead = CompletableFuture.supplyAsync(() -> {
                try {
                    return exchange(session, inbound, 73,
                            "business/workbench/home-info/get", json.createObjectNode());
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, caller);
            readBarrier.awaitArrival();

            JsonNode signedOut = result(exchange(session, inbound, 74,
                    "business/auth/logout", json.createObjectNode()));
            assertThat(signedOut.path("state").asText()).isEqualTo("SIGNED_OUT");
            assertThat(oaSessionRepository.findByDesktopSession(instanceId, desktopSessionId)).get()
                    .satisfies(record -> {
                        assertThat(record.phase().name()).isEqualTo("SIGNED_OUT");
                        assertThat(record.activeCredentialRef()).isNull();
                    });

            readBarrier.release();
            JsonNode stale = blockedRead.get(8, TimeUnit.SECONDS);
            assertThat(stale.at("/error/code").asInt()).isEqualTo(-32016);
            assertThat(stale.at("/error/data/businessCode").asText())
                    .isEqualTo("BUSINESS_SESSION_STALE");
        } finally {
            readBarrier.release();
        }
    }

    @Test
    void droppedReadResponseMapsToStableRetryableNetworkError() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        String pagePath = "/law-api/lawyer/home-config/summary/case-handling-page";
        long callsBefore = oa.pathCount(pagePath);

        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            loginReady(session, inbound, 91);
            ObjectNode params = json.createObjectNode()
                    .put("kind", "CASE")
                    .put("scope", "PERSONAL")
                    .put("pageNo", 1)
                    .put("pageSize", 10);
            params.putObject("filters").put("status", 1);

            oa.disconnectPath = pagePath;
            JsonNode response;
            try {
                response = exchange(session, inbound, 93,
                        "business/workbench/page/get", params);
            } finally {
                oa.disconnectPath = null;
            }

            assertThat(response.at("/error/code").asInt()).isEqualTo(-32040);
            assertThat(response.at("/error/data/businessCode").asText())
                    .isEqualTo("BUSINESS_REMOTE_UNAVAILABLE");
            assertThat(response.at("/error/data/retryable").asBoolean()).isTrue();
        }
        assertThat(oa.pathCount(pagePath) - callsBefore).isPositive();
    }

    @Test
    void ambiguousScheduleCreateIsNeverReplayed() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        String createPath = "/law-api/lawyer/law-schedule/create";
        long callsBefore = oa.pathCount(createPath);

        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            loginReady(session, inbound, 101);
            oa.dropNextResponse(createPath);
            ObjectNode create = scheduleCreateParams(UUID.randomUUID().toString());

            JsonNode first = exchange(session, inbound, 103, "business/schedule/create", create);
            JsonNode duplicate = exchange(session, inbound, 104, "business/schedule/create", create);

            assertThat(first.at("/error/code").asInt()).isEqualTo(-32032);
            assertThat(first.at("/error/data/businessCode").asText()).isEqualTo("BUSINESS_OUTCOME_UNKNOWN");
            assertThat(first.at("/error/data/retryable").asBoolean()).isFalse();
            assertThat(duplicate.at("/error/code").asInt()).isEqualTo(-32032);
            assertThat(duplicate.at("/error/data/businessCode").asText())
                    .isEqualTo("BUSINESS_OUTCOME_UNKNOWN");
        }
        assertThat(oa.pathCount(createPath) - callsBefore).isEqualTo(1);
    }

    @Test
    void ordinaryReconnectAttachesDetachedSessionWithoutUsingStartupRestore() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        long refreshesBefore = oa.pathCount("/law-api/system/auth/refresh-token");
        List<String> firstInbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession first = connect(instanceId, desktopSessionId, firstInbound)) {
            JsonNode candidates = result(exchange(first, firstInbound, 11,
                    "business/auth/tenant-candidates", json.createObjectNode().put("account", "13800138000")));
            String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
            result(exchange(first, firstInbound, 12, "business/auth/login", json.createObjectNode()
                    .put("account", "13800138000").put("candidateId", candidateId).put("password", "Correct123")));
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(oaSessionRepository
                .findByDesktopSession(instanceId, desktopSessionId).orElseThrow().phase().name())
                .isEqualTo("DETACHED"));

        List<String> secondInbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession second = connect(instanceId, desktopSessionId, secondInbound)) {
            JsonNode detached = result(exchange(second, secondInbound, 13,
                    "business/auth/session/get", json.createObjectNode()));
            assertThat(detached.path("state").asText()).isEqualTo("DETACHED");
            String attachHandle = detached.path("attachHandle").asText();
            assertThat(attachHandle).isNotBlank();
            assertThat(detached.path("canAttach").asBoolean()).isTrue();
            JsonNode attached = result(exchange(second, secondInbound, 14,
                    "business/auth/session/attach", json.createObjectNode()
                            .put("attachHandle", attachHandle)));
            assertThat(attached.path("state").asText()).isEqualTo("READY");
            JsonNode retry = result(exchange(second, secondInbound, 15,
                    "business/auth/session/attach", json.createObjectNode()
                            .put("attachHandle", attachHandle)));
            assertThat(retry.path("state").asText()).isEqualTo("READY");
            JsonNode workbench = result(exchange(second, secondInbound, 16,
                    "business/workbench/get", json.createObjectNode()));
            assertThat(workbench.path("snapshot").path("profile").path("status").asText()).isEqualTo("OK");
        }
        assertThat(oa.pathCount("/law-api/system/auth/refresh-token") - refreshesBefore).isEqualTo(1);
        assertThat(invokedRpcMethods).contains("business/auth/session/attach")
                .doesNotContain("business/auth/session/restore");
    }

    @Test
    void startupRestoreRebindsDetachedSessionToANewChildDesktopSession() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String oldDesktopSessionId = UUID.randomUUID().toString();
        String newDesktopSessionId = UUID.randomUUID().toString();
        long refreshesBefore = oa.pathCount("/law-api/system/auth/refresh-token");
        List<String> firstInbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession first = connect(instanceId, oldDesktopSessionId, firstInbound)) {
            JsonNode candidates = result(exchange(first, firstInbound, 21,
                    "business/auth/tenant-candidates", json.createObjectNode().put("account", "13800138000")));
            String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
            result(exchange(first, firstInbound, 22, "business/auth/login", json.createObjectNode()
                    .put("account", "13800138000").put("candidateId", candidateId)
                    .put("password", "Correct123")));
        }

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(oaSessionRepository
                .findByDesktopSession(instanceId, oldDesktopSessionId).orElseThrow().phase().name())
                .isEqualTo("DETACHED"));

        List<String> secondInbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession second = connect(instanceId, newDesktopSessionId, secondInbound)) {
            JsonNode detached = result(exchange(second, secondInbound, 23,
                    "business/auth/session/get", json.createObjectNode()));
            assertThat(detached.path("state").asText()).isEqualTo("DETACHED");
            assertThat(detached.path("generation").asLong()).isPositive();
            assertThat(detached.path("canRestore").asBoolean()).isTrue();
            assertThat(detached.path("canAttach").asBoolean()).isFalse();
            assertThat(detached.path("attachHandle").isNull()
                    || detached.path("attachHandle").isMissingNode()).isTrue();

            JsonNode restored = result(exchange(second, secondInbound, 24,
                    "business/auth/session/restore", json.createObjectNode()));
            assertThat(restored.path("state").asText()).isEqualTo("READY");
            assertThat(oaSessionRepository.findByDesktopSession(instanceId, oldDesktopSessionId)).isEmpty();
            assertThat(oaSessionRepository.findByDesktopSession(instanceId, newDesktopSessionId)).get()
                    .satisfies(record -> assertThat(record.phase().name()).isEqualTo("READY"));
        }
        assertThat(oa.pathCount("/law-api/system/auth/refresh-token") - refreshesBefore).isEqualTo(1);
        assertThat(invokedRpcMethods).contains("business/auth/session/restore")
                .doesNotContain("business/auth/session/attach");
    }

    @Test
    void membershipExpiryRevokesBeforePublishingStateChangedAndReturningTheError() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        List<String> inbound = new CopyOnWriteArrayList<>();
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            JsonNode candidates = result(exchange(session, inbound, 31,
                    "business/auth/tenant-candidates",
                    json.createObjectNode().put("account", "13800138000")));
            String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
            result(exchange(session, inbound, 32, "business/auth/login", json.createObjectNode()
                    .put("account", "13800138000")
                    .put("candidateId", candidateId)
                    .put("password", "Correct123")));

            var ready = oaSessionRepository.findByDesktopSession(instanceId, desktopSessionId).orElseThrow();
            String authSessionId = ready.authSessionId();
            String credentialRef = ready.activeCredentialRef();
            var connection = connections.findByDesktopSessionId(desktopSessionId).orElseThrow();
            assertThat(identities.current(connection)).isPresent();
            assertThat(catalogs.current(connection)).isPresent();
            assertThat(pageContexts.current(connection)).isPresent();

            oa.homeInfoTerminalCode = "1002010000";
            JsonNode response;
            try {
                response = exchange(session, inbound, 33,
                        "business/workbench/home-info/get", json.createObjectNode());
            } finally {
                oa.homeInfoTerminalCode = null;
            }

            assertThat(response.at("/error/code").asInt()).isEqualTo(-32015);
            assertThat(response.at("/error/data/businessCode").asText())
                    .isEqualTo("BUSINESS_MEMBERSHIP_EXPIRED");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(inbound.stream()
                    .map(this::read)
                    .filter(frame -> "business/auth/state-changed".equals(frame.path("method").asText())))
                    .singleElement()
                    .satisfies(notification -> {
                        assertThat(notification.at("/params/authSessionId").asText()).isEqualTo(authSessionId);
                        assertThat(notification.at("/params/state").asText()).isEqualTo("SIGNED_OUT");
                        assertThat(notification.at("/params/businessCode").asText())
                                .isEqualTo("BUSINESS_MEMBERSHIP_EXPIRED");
                    }));

            var signedOut = oaSessionRepository.findByAuthSessionId(authSessionId).orElseThrow();
            JsonNode notification = inbound.stream().map(this::read)
                    .filter(frame -> "business/auth/state-changed".equals(frame.path("method").asText()))
                    .findFirst().orElseThrow();
            assertThat(notification.at("/params/generation").asLong()).isEqualTo(signedOut.generation());
            assertThat(signedOut.phase().name()).isEqualTo("SIGNED_OUT");
            assertThat(signedOut.activeCredentialRef()).isNull();
            assertThat(oaSessionCredentialStore.load(credentialRef)).isNull();
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(pageContexts.current(connection)).isEmpty();

            List<JsonNode> frames = inbound.stream().map(this::read).toList();
            int notificationIndex = java.util.stream.IntStream.range(0, frames.size())
                    .filter(index -> "business/auth/state-changed".equals(
                            frames.get(index).path("method").asText()))
                    .findFirst().orElseThrow();
            int responseIndex = java.util.stream.IntStream.range(0, frames.size())
                    .filter(index -> frames.get(index).path("id").asLong() == 33)
                    .findFirst().orElseThrow();
            assertThat(notificationIndex).isLessThan(responseIndex);
        } finally {
            oa.homeInfoTerminalCode = null;
        }
    }

    @Test
    void secondAuthenticationExpiryAfterRefreshRevokesAllStateAndAllowsSameSocketRelogin() throws Exception {
        String instanceId = UUID.randomUUID().toString();
        String desktopSessionId = UUID.randomUUID().toString();
        List<String> inbound = new CopyOnWriteArrayList<>();
        long refreshesBefore = oa.pathCount("/law-api/system/auth/refresh-token");
        try (WebSocketSession session = connect(instanceId, desktopSessionId, inbound)) {
            JsonNode candidates = result(exchange(session, inbound, 41,
                    "business/auth/tenant-candidates",
                    json.createObjectNode().put("account", "13800138000")));
            String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
            JsonNode firstReady = result(exchange(session, inbound, 42, "business/auth/login",
                    json.createObjectNode()
                            .put("account", "13800138000")
                            .put("candidateId", candidateId)
                            .put("password", "Correct123")));

            var ready = oaSessionRepository.findByDesktopSession(instanceId, desktopSessionId).orElseThrow();
            String authSessionId = ready.authSessionId();
            String firstCredentialRef = ready.activeCredentialRef();
            var connection = connections.findByDesktopSessionId(desktopSessionId).orElseThrow();
            assertThat(identities.current(connection)).isPresent();
            assertThat(catalogs.current(connection)).isPresent();
            assertThat(pageContexts.current(connection)).isPresent();

            oa.homeInfoTerminalCode = "499";
            JsonNode response;
            try {
                response = exchange(session, inbound, 43,
                        "business/workbench/home-info/get", json.createObjectNode());
            } finally {
                oa.homeInfoTerminalCode = null;
            }

            assertThat(response.at("/error/code").asInt()).isEqualTo(-32014);
            assertThat(response.at("/error/data/businessCode").asText())
                    .isEqualTo("BUSINESS_AUTH_EXPIRED");
            assertThat(oa.pathCount("/law-api/system/auth/refresh-token") - refreshesBefore)
                    .isEqualTo(1);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(inbound.stream()
                    .map(this::read)
                    .filter(frame -> "business/auth/state-changed".equals(frame.path("method").asText())))
                    .singleElement()
                    .satisfies(notification -> {
                        assertThat(notification.at("/params/authSessionId").asText()).isEqualTo(authSessionId);
                        assertThat(notification.at("/params/state").asText()).isEqualTo("SIGNED_OUT");
                        assertThat(notification.at("/params/businessCode").asText())
                                .isEqualTo("BUSINESS_AUTH_EXPIRED");
                    }));

            var signedOut = oaSessionRepository.findByAuthSessionId(authSessionId).orElseThrow();
            assertThat(signedOut.phase().name()).isEqualTo("SIGNED_OUT");
            assertThat(signedOut.generation()).isGreaterThan(ready.generation());
            assertThat(signedOut.activeCredentialRef()).isNull();
            assertThat(oaSessionCredentialStore.load(firstCredentialRef)).isNull();
            assertThat(oaSessionCredentialStore.listBusinessOaRefs())
                    .noneMatch(secretRef -> secretRef.contains(authSessionId));
            assertThat(identities.current(connection)).isEmpty();
            assertThat(catalogs.current(connection)).isEmpty();
            assertThat(pageContexts.current(connection)).isEmpty();

            JsonNode notification = inbound.stream().map(this::read)
                    .filter(frame -> "business/auth/state-changed".equals(frame.path("method").asText()))
                    .findFirst().orElseThrow();
            assertThat(notification.at("/params/generation").asLong()).isEqualTo(signedOut.generation());
            List<JsonNode> frames = inbound.stream().map(this::read).toList();
            int notificationIndex = java.util.stream.IntStream.range(0, frames.size())
                    .filter(index -> "business/auth/state-changed".equals(
                            frames.get(index).path("method").asText()))
                    .findFirst().orElseThrow();
            int responseIndex = java.util.stream.IntStream.range(0, frames.size())
                    .filter(index -> frames.get(index).path("id").asLong() == 43)
                    .findFirst().orElseThrow();
            assertThat(notificationIndex).isLessThan(responseIndex);

            JsonNode signedOutView = result(exchange(session, inbound, 44,
                    "business/auth/session/get", json.createObjectNode()));
            assertThat(signedOutView.path("state").asText()).isEqualTo("SIGNED_OUT");
            assertThat(signedOutView.path("rememberedAccount").isNull()
                    || signedOutView.path("rememberedAccount").isMissingNode()).isTrue();

            JsonNode nextCandidates = result(exchange(session, inbound, 45,
                    "business/auth/tenant-candidates",
                    json.createObjectNode().put("account", "13800138000")));
            String nextCandidateId = nextCandidates.path("candidates").path(0).path("candidateId").asText();
            JsonNode secondReady = result(exchange(session, inbound, 46, "business/auth/login",
                    json.createObjectNode()
                            .put("account", "13800138000")
                            .put("candidateId", nextCandidateId)
                            .put("password", "Correct123")));
            assertThat(secondReady.path("state").asText()).isEqualTo("READY");
            assertThat(secondReady.path("identityEpoch").asLong())
                    .isGreaterThan(firstReady.path("identityEpoch").asLong());
            assertThat(identities.current(connection)).isPresent();
            assertThat(catalogs.current(connection)).isPresent();
            assertThat(pageContexts.current(connection)).isPresent();
        } finally {
            oa.homeInfoTerminalCode = null;
        }
    }

    private WebSocketSession connect(String instanceId, String desktopSessionId, List<String> inbound)
            throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        headers.set("X-Desktop-Instance-Id", instanceId);
        headers.set("X-Desktop-Session-Id", desktopSessionId);
        headers.setOrigin("http://127.0.0.1");
        return new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                inbound.add(message.getPayload());
            }
        }, headers, URI.create("ws://127.0.0.1:" + port + "/ws/agent")).get(8, TimeUnit.SECONDS);
    }

    private JsonNode loginReady(WebSocketSession session, List<String> inbound, long firstRequestId)
            throws Exception {
        JsonNode candidates = result(exchange(session, inbound, firstRequestId,
                "business/auth/tenant-candidates",
                json.createObjectNode().put("account", "13800138000")));
        String candidateId = candidates.path("candidates").path(0).path("candidateId").asText();
        JsonNode ready = result(exchange(session, inbound, firstRequestId + 1,
                "business/auth/login", json.createObjectNode()
                        .put("account", "13800138000")
                        .put("candidateId", candidateId)
                        .put("password", "Correct123")));
        assertThat(ready.path("state").asText()).isEqualTo("READY");
        return ready;
    }

    private ObjectNode scheduleCreateParams(String operationId) {
        ObjectNode create = json.createObjectNode()
                .put("clientOperationId", operationId)
                .put("scope", "PERSONAL")
                .put("title", "Prepare hearing")
                .put("typeId", "type-1")
                .put("at", "2026-07-29 09:30:00")
                .put("allDay", false)
                .put("priority", 2)
                .put("description", "Bring the case file");
        create.putArray("reminderMinutes").add(15);
        create.putArray("relations");
        return create;
    }

    private JsonNode exchange(WebSocketSession session, List<String> inbound, long id, String method, JsonNode params)
            throws Exception {
        invokedRpcMethods.add(method);
        String frame = json.writeValueAsString(json.createObjectNode().put("jsonrpc", "2.0")
                .put("id", id).put("method", method).set("params", params));
        session.sendMessage(new TextMessage(frame));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(inbound.stream()
                .map(this::read).anyMatch(node -> !node.has("method") && node.path("id").asLong() == id)).isTrue());
        return inbound.stream().map(this::read)
                .filter(node -> !node.has("method") && node.path("id").asLong() == id)
                .findFirst().orElseThrow();
    }

    private JsonNode result(JsonNode response) {
        assertThat(response.path("error").isMissingNode()).as(response.toString()).isTrue();
        return response.path("result");
    }

    private JsonNode read(String payload) {
        try {
            return json.readTree(payload);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class RecordingOaServer {
        private final HttpServer server;
        private final ExecutorService executor;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final Map<String, Task17BusinessGatewayHarness.RequestBarrier> nextBarriers =
                new ConcurrentHashMap<>();
        private final List<Task17BusinessGatewayHarness.RequestBarrier> barriers = new CopyOnWriteArrayList<>();
        private final Set<String> dropNextResponses = ConcurrentHashMap.newKeySet();
        private volatile String homeInfoTerminalCode;
        private volatile boolean expireOriginalHomeInfoAccess;
        private volatile boolean expireOriginalScheduleAccess;
        private volatile String disconnectPath;

        private RecordingOaServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static RecordingOaServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(
                    Thread.ofPlatform().daemon(true).name("workbench-e2e-oa-", 0).factory());
            RecordingOaServer recording = new RecordingOaServer(server, executor);
            server.setExecutor(executor);
            server.createContext("/", recording::handle);
            server.start();
            return recording;
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        void stop() {
            barriers.forEach(Task17BusinessGatewayHarness.RequestBarrier::release);
            server.stop(0);
            executor.shutdownNow();
        }

        List<String> paths() {
            return requests.stream().map(Request::path).toList();
        }

        long authorizedRequests() {
            return requests.stream().filter(request -> request.header("Authorization") != null).count();
        }

        long pathCount(String path) {
            return requests.stream().filter(request -> request.path().equals(path)).count();
        }

        List<Request> requestsTo(String path) {
            return requests.stream().filter(request -> request.path().equals(path)).toList();
        }

        long pathCountWithAuthorization(String path, String authorization) {
            return requests.stream().filter(request -> request.path().equals(path))
                    .filter(request -> authorization.equals(request.header("Authorization"))).count();
        }

        Task17BusinessGatewayHarness.RequestBarrier blockNext(String path) {
            Task17BusinessGatewayHarness.RequestBarrier barrier =
                    new Task17BusinessGatewayHarness.RequestBarrier();
            if (nextBarriers.putIfAbsent(path, barrier) != null) {
                throw new IllegalStateException("A fake OA barrier is already registered for " + path);
            }
            barriers.add(barrier);
            return barrier;
        }

        void dropNextResponse(String path) {
            if (!dropNextResponses.add(path)) {
                throw new IllegalStateException("A dropped response is already registered for " + path);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), body,
                    exchange.getRequestHeaders().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey, entry -> entry.getValue().stream().findFirst().orElse(""),
                            (left, right) -> left)));
            requests.add(request);
            String path = request.path();
            if (path.equals(disconnectPath)) {
                exchange.close();
                return;
            }
            if (dropNextResponses.remove(path)) {
                exchange.close();
                return;
            }
            String response = switch (path) {
                case "/law-api/system/auth/get-users-by-mobile" ->
                        "{\"code\":0,\"msg\":\"\",\"data\":[{\"userId\":1001,\"tenantId\":2002,\"platformId\":2,\"tenantName\":\"Test Tenant\",\"tenantEnterStatus\":1}]}";
                case "/law-api/system/auth/login" ->
                        "{\"code\":0,\"msg\":\"\",\"data\":{\"accessToken\":\"access-e2e\",\"refreshToken\":\"refresh-e2e\",\"userId\":1001,\"expiresTime\":9999999999}}";
                case "/law-api/system/auth/refresh-token" ->
                        "{\"code\":0,\"msg\":\"\",\"data\":{\"accessToken\":\"access-refresh\",\"refreshToken\":\"refresh-refresh\",\"userId\":1001,\"expiresTime\":9999999999}}";
                case "/law-api/system/auth/get-permission-info" ->
                        "{\"code\":0,\"msg\":\"\",\"data\":{\"permissions\":[\"law:case:query\"],\"roles\":[\"lawyer\"],\"user\":{\"id\":1001,\"nickname\":\"Alice\"},\"menus\":[]}}";
                case "/law-api/system/notice-push/page" ->
                        "{\"code\":0,\"data\":{\"total\":1,\"pageNo\":1,\"pageSize\":10,\"list\":[{\"id\":\"notice-1\",\"title\":\"Welcome\",\"token\":\"remote-notice-token\"}]}}";
                case "/law-api/lawyer/home-config/list-shortcut" ->
                        "{\"code\":0,\"data\":[{\"id\":\"shortcut-1\",\"name\":\"Cases\",\"url\":\"/case\",\"token\":\"remote-shortcut-token\"}]}";
                case "/law-api/lawyer/home-config/summary" ->
                        "{\"code\":0,\"data\":[{\"id\":\"summary-1\",\"configName\":\"Pending\",\"total\":3,\"stat\":{\"handling\":2}}]}";
                case "/law-api/system/user/home-info" -> homeInfo(request);
                case "/law-api/system/team/list" ->
                        "{\"code\":0,\"data\":[{\"id\":\"team-1\",\"name\":\"Team One\",\"userId\":1001}]}";
                case "/law-api/lawyer/law-schedule/list-count" -> scheduleCount(request);
                case "/law-api/lawyer/law-schedule/list-day" -> scheduleDay(request);
                case "/law-api/lawyer/home-config/summary/case-handling-page" -> page("case");
                case "/law-api/lawyer/home-config/summary/appointment-page" -> page("appointment");
                case "/law-api/counselor/home-config/summary/counselor-service-page" -> page("counselor_service");
                case "/law-api/counselor/home-config/summary/visiting-page" -> page("visit");
                case "/law-api/lawyer/home-config/update-sort" ->
                        "{\"code\":0,\"data\":true}";
                case "/law-api/lawyer/law-schedule-type/page" ->
                        "{\"code\":0,\"data\":[{\"id\":\"type-1\",\"name\":\"Hearing\"}]}";
                case "/law-api/lawyer/law-schedule/create" ->
                        "{\"code\":0,\"data\":{\"id\":\"schedule-created\"}}";
                default -> "{\"code\":0,\"data\":true}";
            };
            Task17BusinessGatewayHarness.RequestBarrier barrier = nextBarriers.remove(path);
            if (barrier != null) barrier.block();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(expiredScheduleRequest(request) ? 401 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private String homeInfo(Request request) {
            if (homeInfoTerminalCode != null) {
                return "{\"code\":" + homeInfoTerminalCode
                        + ",\"msg\":\"terminal-auth-canary\",\"data\":null}";
            }
            if (expireOriginalHomeInfoAccess
                    && "Bearer access-e2e".equals(request.header("Authorization"))) {
                return "{\"code\":499,\"msg\":\"expired\",\"data\":null}";
            }
            return "{\"code\":0,\"data\":{\"userId\":1001,\"tenantId\":2002,"
                    + "\"nickname\":\"Alice\",\"avatar\":\"avatar-handle\","
                    + "\"password\":\"remote-password\"}}";
        }

        private String scheduleCount(Request request) {
            return "{\"code\":0,\"data\":{\"schDate\":\"2026-07-27\",\"schCount\":1}}";
        }

        private String scheduleDay(Request request) {
            return "{\"code\":0,\"data\":{\"list\":[{\"id\":\"schedule-1\",\"title\":\"Review\","
                    + "\"schTime\":\"10:00\",\"token\":\"remote-schedule-token\"}]}}";
        }

        private boolean expiredScheduleRequest(Request request) {
            return expireOriginalScheduleAccess
                    && ("/law-api/lawyer/law-schedule/list-count".equals(request.path())
                    || "/law-api/lawyer/law-schedule/list-day".equals(request.path()))
                    && "Bearer access-e2e".equals(request.header("Authorization"));
        }

        private static String page(String kind) {
            return "{\"code\":0,\"data\":{\"total\":1,\"pageNo\":1,\"pageSize\":10,\"list\":[{"
                    + "\"id\":\"" + kind + "-1\","
                    + "\"title\":\"Task 17 row\","
                    + "\"applicationNumber\":\"APP-17\","
                    + "\"categoriesName\":\"Category\","
                    + "\"scheduleName\":\"Pending\","
                    + "\"accessToken\":\"remote-page-token\"}]}}";
        }

        private record Request(String method, String path, String query, String body, Map<String, String> headers) {
            String header(String name) {
                return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }

            @Override
            public String toString() {
                return "RecordingOaRequest([REDACTED])";
            }
        }
    }

    @Test
    void recording_oa_request_to_string_is_fixed_and_redacted() {
        var request = new RecordingOaServer.Request(
                "POST",
                "/private/path",
                "accessToken=query-secret",
                "{\"password\":\"body-secret\"}",
                Map.of("Authorization", "Bearer header-secret"));

        assertThat(request.toString()).isEqualTo("RecordingOaRequest([REDACTED])");
    }
}
