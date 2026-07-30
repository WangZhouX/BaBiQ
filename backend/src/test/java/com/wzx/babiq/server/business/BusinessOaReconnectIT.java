package com.wzx.babiq.server.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.BaBiQApplication;
import com.wzx.babiq.server.application.auth.ApplicationIdentityRegistry;
import com.wzx.babiq.server.application.auth.BusinessDesktopConnectionRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationCatalogRegistry;
import com.wzx.babiq.server.application.catalog.ApplicationPageContextRegistry;
import com.wzx.babiq.server.business.oa.session.OaSessionCredentialStore;
import com.wzx.babiq.server.business.oa.session.OaSessionRecord;
import com.wzx.babiq.server.business.oa.session.OaSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Real local-WebSocket reconnect and process-restart coverage for the server-owned OA session. */
@ResourceLock("logback-root")
class BusinessOaReconnectIT {
    private static final String KEYSTORE_PASSWORD = "task17-oa-reconnect-password";
    private static final String REFRESH_PATH = "/law-api/system/auth/refresh-token";
    private static final String PERMISSION_PATH = "/law-api/system/auth/get-permission-info";
    private static final Task17BusinessGatewayHarness.Account ACCOUNT =
            new Task17BusinessGatewayHarness.Account(
                    "13800138000", "1001", "2002", 2,
                    "Alpha Firm", "Alice", "access-initial", "refresh-initial");

    @Test
    void successorSocketAttachesDetachedSessionForTheSameDesktopSlot() throws Exception {
        RuntimeLayout layout = RuntimeLayout.create("attach");
        try (Task17BusinessGatewayHarness.FakeOaServer oa =
                     Task17BusinessGatewayHarness.FakeOaServer.start(ACCOUNT);
             ServletWebServerApplicationContext context =
                     start(layout, desktopToken(1), oa)) {
            ObjectMapper json = context.getBean(ObjectMapper.class);
            OaSessionRepository sessions = context.getBean(OaSessionRepository.class);
            BusinessDesktopConnectionRegistry connections =
                    context.getBean(BusinessDesktopConnectionRegistry.class);
            String desktopInstanceId = UUID.randomUUID().toString();
            String desktopSessionId = UUID.randomUUID().toString();
            String authSessionId;
            long readyGeneration;

            try (Task17BusinessGatewayHarness.RealWebSocketRpcSession first =
                         connect(context, json, desktopToken(1), desktopInstanceId, desktopSessionId)) {
                JsonNode ready = login(json, first, ACCOUNT);
                assertThat(ready.path("state").asText()).isEqualTo("READY");
                authSessionId = ready.path("authSessionId").asText();
                readyGeneration = ready.path("generation").asLong();
            }

            awaitDetached(sessions, connections, desktopInstanceId, desktopSessionId);

            try (Task17BusinessGatewayHarness.RealWebSocketRpcSession successor =
                         connect(context, json, desktopToken(1), desktopInstanceId, desktopSessionId)) {
                JsonNode detached = successor.result(
                        "business/auth/session/get", json.createObjectNode());
                assertThat(detached.path("state").asText()).isEqualTo("DETACHED");
                assertThat(detached.path("authSessionId").asText()).isEqualTo(authSessionId);
                assertThat(detached.path("generation").asLong()).isGreaterThan(readyGeneration);
                assertThat(detached.path("canAttach").asBoolean()).isTrue();
                assertThat(detached.path("canRestore").asBoolean()).isFalse();
                String attachHandle = detached.path("attachHandle").asText();
                assertThat(attachHandle).isNotBlank();

                JsonNode attached = successor.result(
                        "business/auth/session/attach",
                        json.createObjectNode().put("attachHandle", attachHandle));

                assertThat(attached.path("state").asText()).isEqualTo("READY");
                assertThat(attached.path("authSessionId").asText()).isEqualTo(authSessionId);
                assertThat(attached.path("generation").asLong())
                        .isGreaterThan(detached.path("generation").asLong());
                assertThat(attached.path("userId").asText()).isEqualTo(ACCOUNT.userId());
                assertThat(sessions.findByDesktopSession(desktopInstanceId, desktopSessionId)).get()
                        .satisfies(record -> {
                            assertThat(record.phase().name()).isEqualTo("READY");
                            assertThat(record.authSessionId()).isEqualTo(authSessionId);
                            assertThat(record.generation()).isEqualTo(attached.path("generation").asLong());
                        });
            }

            assertThat(oa.requestsTo(REFRESH_PATH)).singleElement().satisfies(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.header("tenant-id")).isEqualTo(ACCOUNT.tenantId());
                assertThat(request.body()).contains("refreshToken=" + ACCOUNT.refreshToken());
            });
        } finally {
            Task17BusinessGatewayHarness.deleteTree(layout.root());
        }
    }

    @Test
    void secondServletContextRestoresDetachedSessionFromTheSameDatabaseAndKeyStore() throws Exception {
        RuntimeLayout layout = RuntimeLayout.create("restart");
        String firstToken = desktopToken(2);
        String secondToken = desktopToken(3);
        String desktopInstanceId = UUID.randomUUID().toString();
        String oldDesktopSessionId = UUID.randomUUID().toString();
        String newDesktopSessionId = UUID.randomUUID().toString();
        String authSessionId;
        long detachedGeneration;

        try (Task17BusinessGatewayHarness.FakeOaServer oa =
                     Task17BusinessGatewayHarness.FakeOaServer.start(ACCOUNT)) {
            try (ServletWebServerApplicationContext firstContext = start(layout, firstToken, oa)) {
                ObjectMapper json = firstContext.getBean(ObjectMapper.class);
                OaSessionRepository sessions = firstContext.getBean(OaSessionRepository.class);
                BusinessDesktopConnectionRegistry connections =
                        firstContext.getBean(BusinessDesktopConnectionRegistry.class);
                assertThat(Files.notExists(layout.sessionToken())).isTrue();

                try (Task17BusinessGatewayHarness.RealWebSocketRpcSession first =
                             connect(firstContext, json, firstToken,
                                     desktopInstanceId, oldDesktopSessionId)) {
                    JsonNode ready = login(json, first, ACCOUNT);
                    assertThat(ready.path("state").asText()).isEqualTo("READY");
                    authSessionId = ready.path("authSessionId").asText();
                }

                awaitDetached(sessions, connections, desktopInstanceId, oldDesktopSessionId);
                detachedGeneration = sessions.findByDesktopSession(
                        desktopInstanceId, oldDesktopSessionId).orElseThrow().generation();
            }

            try (ServletWebServerApplicationContext secondContext = start(layout, secondToken, oa)) {
                ObjectMapper json = secondContext.getBean(ObjectMapper.class);
                OaSessionRepository sessions = secondContext.getBean(OaSessionRepository.class);
                assertThat(Files.notExists(layout.sessionToken())).isTrue();

                try (Task17BusinessGatewayHarness.RealWebSocketRpcSession second =
                             connect(secondContext, json, secondToken,
                                     desktopInstanceId, newDesktopSessionId)) {
                    JsonNode detached = second.result(
                            "business/auth/session/get", json.createObjectNode());
                    assertThat(detached.path("state").asText()).isEqualTo("DETACHED");
                    assertThat(detached.path("authSessionId").asText()).isEqualTo(authSessionId);
                    assertThat(detached.path("generation").asLong()).isEqualTo(detachedGeneration);
                    assertThat(detached.path("canRestore").asBoolean()).isTrue();
                    assertThat(detached.path("canAttach").asBoolean()).isFalse();
                    assertThat(detached.path("attachHandle").isMissingNode()
                            || detached.path("attachHandle").isNull()).isTrue();

                    JsonNode restored = second.result(
                            "business/auth/session/restore", json.createObjectNode());

                    assertThat(restored.path("state").asText()).isEqualTo("READY");
                    assertThat(restored.path("authSessionId").asText()).isEqualTo(authSessionId);
                    assertThat(restored.path("generation").asLong()).isGreaterThan(detachedGeneration);
                    assertThat(restored.path("identityEpoch").asLong()).isPositive();
                    assertThat(sessions.findByDesktopSession(
                            desktopInstanceId, oldDesktopSessionId)).isEmpty();
                    assertThat(sessions.findByDesktopSession(
                            desktopInstanceId, newDesktopSessionId)).get().satisfies(record -> {
                                assertThat(record.phase().name()).isEqualTo("READY");
                                assertThat(record.authSessionId()).isEqualTo(authSessionId);
                                assertThat(record.generation())
                                        .isEqualTo(restored.path("generation").asLong());
                            });
                }
            }

            assertThat(oa.requestsTo(REFRESH_PATH)).hasSize(1);
            assertThat(oa.requestsTo(PERMISSION_PATH)).hasSize(2);
        } finally {
            Task17BusinessGatewayHarness.deleteTree(layout.root());
        }
    }

    @Test
    void delayedRefreshFromClosedSocketCannotOverwriteSuccessorReadyGeneration() throws Exception {
        RuntimeLayout layout = RuntimeLayout.create("late-refresh");
        String desktopToken = desktopToken(4);
        try (Task17BusinessGatewayHarness.FakeOaServer oa =
                     Task17BusinessGatewayHarness.FakeOaServer.start(ACCOUNT);
             ServletWebServerApplicationContext context = start(layout, desktopToken, oa)) {
            ObjectMapper json = context.getBean(ObjectMapper.class);
            OaSessionRepository sessions = context.getBean(OaSessionRepository.class);
            OaSessionCredentialStore credentials = context.getBean(OaSessionCredentialStore.class);
            BusinessDesktopConnectionRegistry connections =
                    context.getBean(BusinessDesktopConnectionRegistry.class);
            ApplicationIdentityRegistry identities = context.getBean(ApplicationIdentityRegistry.class);
            ApplicationCatalogRegistry catalogs = context.getBean(ApplicationCatalogRegistry.class);
            ApplicationPageContextRegistry pageContexts =
                    context.getBean(ApplicationPageContextRegistry.class);
            String desktopInstanceId = UUID.randomUUID().toString();
            String desktopSessionId = UUID.randomUUID().toString();
            String authSessionId;

            try (Task17BusinessGatewayHarness.RealWebSocketRpcSession seed =
                         connect(context, json, desktopToken, desktopInstanceId, desktopSessionId)) {
                JsonNode ready = login(json, seed, ACCOUNT);
                authSessionId = ready.path("authSessionId").asText();
                assertThat(ready.path("state").asText()).isEqualTo("READY");
            }
            awaitDetached(sessions, connections, desktopInstanceId, desktopSessionId);

            Task17BusinessGatewayHarness.RealWebSocketRpcSession stale =
                    connect(context, json, desktopToken, desktopInstanceId, desktopSessionId);
            ExecutorService staleRequestExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("task17-stale-rpc-", 0).factory());
            oa.enqueueRefreshResponse(
                    ACCOUNT.refreshToken(), "access-old", "refresh-old", ACCOUNT.userId());
            oa.enqueueRefreshResponse(
                    ACCOUNT.refreshToken(), "access-new", "refresh-new", ACCOUNT.userId());
            Task17BusinessGatewayHarness.RequestBarrier refreshBarrier = oa.blockNext(REFRESH_PATH);
            Future<JsonNode> staleAttach = null;
            boolean staleClosed = false;
            try {
                JsonNode staleDetached = stale.result(
                        "business/auth/session/get", json.createObjectNode());
                String staleAttachHandle = staleDetached.path("attachHandle").asText();
                assertThat(staleAttachHandle).isNotBlank();

                staleAttach = staleRequestExecutor.submit(() -> stale.result(
                        "business/auth/session/attach",
                        json.createObjectNode().put("attachHandle", staleAttachHandle)));
                refreshBarrier.awaitArrival();

                stale.close();
                staleClosed = true;
                awaitDetached(sessions, connections, desktopInstanceId, desktopSessionId);

                try (Task17BusinessGatewayHarness.RealWebSocketRpcSession successor =
                             connect(context, json, desktopToken,
                                     desktopInstanceId, desktopSessionId)) {
                    JsonNode successorDetached = successor.result(
                            "business/auth/session/get", json.createObjectNode());
                    String successorAttachHandle = successorDetached.path("attachHandle").asText();
                    assertThat(successorAttachHandle).isNotBlank();

                    JsonNode successorReady = successor.result(
                            "business/auth/session/attach",
                            json.createObjectNode().put("attachHandle", successorAttachHandle));
                    long successorGeneration = successorReady.path("generation").asLong();
                    long successorIdentityEpoch = successorReady.path("identityEpoch").asLong();
                    OaSessionRecord successorRecord = sessions.findByDesktopSession(
                            desktopInstanceId, desktopSessionId).orElseThrow();

                    assertThat(successorReady.path("state").asText()).isEqualTo("READY");
                    assertThat(successorReady.path("authSessionId").asText()).isEqualTo(authSessionId);
                    assertThat(successorGeneration)
                            .isGreaterThan(successorDetached.path("generation").asLong());
                    assertActiveCredential(
                            credentials, successorRecord, "access-new", "refresh-new");

                    refreshBarrier.release();
                    Future<JsonNode> completedStaleAttach = staleAttach;
                    assertThatThrownBy(() -> completedStaleAttach.get(12, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class);

                    assertThat(oa.requestsTo(REFRESH_PATH)).hasSize(2);
                    assertThat(oa.requestsTo(PERMISSION_PATH)).hasSize(3);
                    assertThat(sessions.findByDesktopSession(
                            desktopInstanceId, desktopSessionId)).get().satisfies(record -> {
                                assertThat(record.phase().name()).isEqualTo("READY");
                                assertThat(record.authSessionId()).isEqualTo(authSessionId);
                                assertThat(record.generation()).isEqualTo(successorGeneration);
                                assertActiveCredential(
                                        credentials, record, "access-new", "refresh-new");
                            });

                    var successorConnection = connections.findByDesktopSessionId(
                            desktopSessionId).orElseThrow();
                    assertThat(identities.current(successorConnection)).get().satisfies(identity -> {
                        assertThat(identity.authSessionId()).isEqualTo(authSessionId);
                        assertThat(identity.identityEpoch()).isEqualTo(successorIdentityEpoch);
                    });
                    assertThat(catalogs.current(successorConnection)).isPresent();
                    assertThat(pageContexts.current(successorConnection)).isPresent();

                    JsonNode reconciled = successor.result(
                            "business/auth/session/get", json.createObjectNode());
                    assertThat(reconciled.path("state").asText()).isEqualTo("READY");
                    assertThat(reconciled.path("generation").asLong()).isEqualTo(successorGeneration);
                    assertThat(reconciled.path("identityEpoch").asLong())
                            .isEqualTo(successorIdentityEpoch);
                }
            } finally {
                refreshBarrier.release();
                if (staleAttach != null) {
                    staleAttach.cancel(true);
                }
                staleRequestExecutor.shutdownNow();
                if (!staleClosed) {
                    stale.close();
                }
            }
        } finally {
            Task17BusinessGatewayHarness.deleteTree(layout.root());
        }
    }

    private static Task17BusinessGatewayHarness.RealWebSocketRpcSession connect(
            ServletWebServerApplicationContext context,
            ObjectMapper json,
            String desktopToken,
            String desktopInstanceId,
            String desktopSessionId) throws Exception {
        return Task17BusinessGatewayHarness.RealWebSocketRpcSession.connect(
                json,
                context.getWebServer().getPort(),
                desktopToken,
                desktopInstanceId,
                desktopSessionId);
    }

    private static JsonNode login(
            ObjectMapper json,
            Task17BusinessGatewayHarness.RealWebSocketRpcSession rpc,
            Task17BusinessGatewayHarness.Account account) throws Exception {
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
                        .put("password", "Password8"));
    }

    private static void awaitDetached(
            OaSessionRepository sessions,
            BusinessDesktopConnectionRegistry connections,
            String desktopInstanceId,
            String desktopSessionId) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(connections.findByDesktopSessionId(desktopSessionId)).isEmpty();
            assertThat(sessions.findByDesktopSession(desktopInstanceId, desktopSessionId)).get()
                    .satisfies(record -> assertThat(record.phase().name()).isEqualTo("DETACHED"));
        });
    }

    private static void assertActiveCredential(
            OaSessionCredentialStore credentials,
            OaSessionRecord record,
            String expectedAccessToken,
            String expectedRefreshToken) {
        try (OaSessionCredentialStore.CredentialMaterial material =
                     credentials.load(record.activeCredentialRef())) {
            assertThat(material).isNotNull();
            assertThat(material.accessToken()).isEqualTo(expectedAccessToken.toCharArray());
            assertThat(material.refreshToken()).isEqualTo(expectedRefreshToken.toCharArray());
        }
    }

    private static ServletWebServerApplicationContext start(
            RuntimeLayout layout,
            String desktopToken,
            Task17BusinessGatewayHarness.FakeOaServer oa) throws Exception {
        Files.createDirectories(layout.root());
        Files.writeString(layout.sessionToken(), desktopToken, StandardCharsets.US_ASCII);
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BaBiQApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("business-desktop")
                .registerShutdownHook(false)
                .logStartupInfo(false)
                .properties(
                        "spring.main.banner-mode=off",
                        "server.port=0",
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
                        "--huitai.oa.base-url=" + oa.baseUrl(),
                        "--huitai.oa.allow-private-http=true");
    }

    private static String desktopToken(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
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

        static RuntimeLayout create(String name) {
            Path root = Path.of(
                            "target",
                            "business-oa-reconnect-" + name + "-" + UUID.randomUUID())
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
