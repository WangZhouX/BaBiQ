package com.wzx.babiq.server.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Test-only fake OA and real local-WebSocket client shared by Task 17 integration tests. */
final class Task17BusinessGatewayHarness {
    private static final ObjectMapper JSON = new ObjectMapper();

    private Task17BusinessGatewayHarness() {
    }

    record Account(
            String account,
            String userId,
            String tenantId,
            int platformId,
            String tenantName,
            String userName,
            String accessToken,
            String refreshToken) {

        @Override
        public String toString() {
            return "Account[account=" + account + ", userId=" + userId + ", tenantId=" + tenantId
                    + ", platformId=" + platformId + ", tenantName=" + tenantName + ", userName="
                    + userName + ", accessToken=[REDACTED], refreshToken=[REDACTED]]";
        }
    }

    record RecordedRequest(
            String method,
            String path,
            String query,
            String body,
            Map<String, String> headers) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public String toString() {
            return "RecordedRequest[method=" + method + ", path=" + path
                    + ", query=[REDACTED], body=[REDACTED], headerNames=" + headers.keySet() + "]";
        }
    }

    record RefreshCredential(String accessToken, String refreshToken, String userId) {

        @Override
        public String toString() {
            return "RefreshCredential[accessToken=[REDACTED], refreshToken=[REDACTED], userId="
                    + userId + "]";
        }
    }

    record FailureResponse(int httpStatus, String body) {

        @Override
        public String toString() {
            return "FailureResponse[httpStatus=" + httpStatus + ", body=[REDACTED]]";
        }
    }

    static final class FakeOaServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, Account> accounts;
        private final Map<String, Account> accessOwners = new ConcurrentHashMap<>();
        private final Map<String, Account> refreshOwners = new ConcurrentHashMap<>();
        private final Map<String, Queue<RefreshCredential>> refreshResponses = new ConcurrentHashMap<>();
        private final Map<String, Queue<FailureResponse>> failureResponses = new ConcurrentHashMap<>();
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private final Map<String, RequestBarrier> nextBarriers = new ConcurrentHashMap<>();
        private final List<RequestBarrier> barriers = new CopyOnWriteArrayList<>();

        private FakeOaServer(HttpServer server, ExecutorService executor, List<Account> accounts) {
            this.server = server;
            this.executor = executor;
            Map<String, Account> byAccount = new LinkedHashMap<>();
            accounts.forEach(account -> {
                byAccount.put(account.account(), account);
                accessOwners.put(account.accessToken(), account);
                refreshOwners.put(account.refreshToken(), account);
            });
            this.accounts = Map.copyOf(byAccount);
        }

        static FakeOaServer start(Account... accounts) {
            try {
                HttpServer server = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool(
                        Thread.ofPlatform().daemon(true).name("task17-fake-oa-", 0).factory());
                FakeOaServer fake = new FakeOaServer(server, executor, Arrays.asList(accounts));
                server.setExecutor(executor);
                server.createContext("/", fake::handle);
                server.start();
                return fake;
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to start Task 17 fake OA", failure);
            }
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        List<RecordedRequest> requestsTo(String path) {
            return requests.stream().filter(request -> request.path().equals(path)).toList();
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        RequestBarrier blockNext(String path) {
            RequestBarrier barrier = new RequestBarrier();
            if (nextBarriers.putIfAbsent(path, barrier) != null) {
                throw new IllegalStateException("A fake OA barrier is already registered for " + path);
            }
            barriers.add(barrier);
            return barrier;
        }

        void enqueueRefreshResponse(
                String presentedRefreshToken,
                String nextAccessToken,
                String nextRefreshToken,
                String userId) {
            Account owner = accountByRefreshToken(presentedRefreshToken);
            if (!owner.userId().equals(userId)) {
                throw new IllegalArgumentException("refresh response user mismatch");
            }
            refreshResponses.computeIfAbsent(presentedRefreshToken, ignored -> new ConcurrentLinkedQueue<>())
                    .add(new RefreshCredential(nextAccessToken, nextRefreshToken, userId));
            accessOwners.put(nextAccessToken, owner);
            refreshOwners.put(nextRefreshToken, owner);
        }

        void respondNextError(String path, int httpStatus, int code, String message) {
            ObjectNode response = JSON.createObjectNode()
                    .put("code", code)
                    .put("msg", message);
            response.putNull("data");
            failureResponses.computeIfAbsent(path, ignored -> new ConcurrentLinkedQueue<>())
                    .add(new FailureResponse(httpStatus, encode(response)));
        }

        private void handle(HttpExchange exchange) throws IOException {
            RecordedRequest request = record(exchange);
            String response;
            int status = 200;
            try {
                response = switch (request.path()) {
                    case "/law-api/system/auth/get-users-by-mobile" -> tenantCandidates(request);
                    case "/law-api/system/auth/login" -> login(request);
                    case "/law-api/system/auth/refresh-token" -> refresh(request);
                    case "/law-api/system/auth/get-permission-info" -> permissions(request);
                    case "/law-api/system/auth/logout" -> logout(request);
                    default -> {
                        status = 404;
                        yield "{\"code\":404,\"msg\":\"not found\",\"data\":null}";
                    }
                };
                Queue<FailureResponse> queuedFailures = failureResponses.get(request.path());
                FailureResponse failure = queuedFailures == null ? null : queuedFailures.poll();
                if (queuedFailures != null && queuedFailures.isEmpty()) {
                    failureResponses.remove(request.path(), queuedFailures);
                }
                if (failure != null) {
                    status = failure.httpStatus();
                    response = failure.body();
                }
                RequestBarrier barrier = nextBarriers.remove(request.path());
                if (barrier != null) {
                    barrier.block();
                }
            } catch (RuntimeException failure) {
                status = 500;
                response = "{\"code\":500,\"msg\":\"fake OA contract failure\",\"data\":null}";
            }
            write(exchange, status, response);
        }

        private RecordedRequest record(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    headers.put(name, values.stream().findFirst().orElse("")));
            RecordedRequest request = new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    body,
                    Map.copyOf(headers));
            requests.add(request);
            return request;
        }

        private String tenantCandidates(RecordedRequest request) {
            requireRequest(request, "GET");
            Account account = account(queryParameter(request.query(), "mobile"));
            ObjectNode candidate = JSON.createObjectNode()
                    .put("userId", account.userId())
                    .put("tenantId", account.tenantId())
                    .put("platformId", account.platformId())
                    .put("tenantName", account.tenantName())
                    .put("tenantEnterStatus", 1);
            return success(JSON.createArrayNode().add(candidate));
        }

        private String login(RecordedRequest request) {
            try {
                requireRequest(request, "POST");
                JsonNode body = JSON.readTree(request.body());
                Account account = account(body.path("mobileOrEmail").asText());
                if (!body.path("tenantId").asText().equals(account.tenantId())
                        || body.path("platformId").asInt() != account.platformId()
                        || !body.path("password").isTextual()
                        || body.path("password").asText().isBlank()) {
                    throw new IllegalArgumentException("candidate scope mismatch");
                }
                ObjectNode credential = JSON.createObjectNode()
                        .put("accessToken", account.accessToken())
                        .put("refreshToken", account.refreshToken())
                        .put("userId", account.userId())
                        .put("expiresTime", 9_999_999_999L);
                return success(credential);
            } catch (IOException failure) {
                throw new IllegalArgumentException("invalid login JSON", failure);
            }
        }

        private String permissions(RecordedRequest request) {
            requireRequest(request, "GET");
            Account account = accountByAccessToken(bearer(request));
            if (!account.tenantId().equals(request.header("tenant-id"))
                    || !Integer.toString(account.platformId())
                    .equals(queryParameter(request.query(), "platformId"))) {
                throw new IllegalArgumentException("permission tenant mismatch");
            }
            ObjectNode permission = JSON.createObjectNode();
            permission.putArray("permissions").add("law:case:query");
            permission.putArray("roles").add("lawyer");
            permission.set("user", JSON.createObjectNode()
                    .put("id", account.userId())
                    .put("name", account.userName()));
            permission.putArray("menus");
            return success(permission);
        }

        private String refresh(RecordedRequest request) {
            requireRequest(request, "POST");
            if (request.header("Authorization") != null
                    || request.header("Content-Type") == null
                    || !request.header("Content-Type").startsWith("application/x-www-form-urlencoded")) {
                throw new IllegalArgumentException("unexpected refresh request contract");
            }
            String presentedRefreshToken = queryParameter(request.body(), "refreshToken");
            Account account = accountByRefreshToken(presentedRefreshToken);
            if (!account.tenantId().equals(request.header("tenant-id"))) {
                throw new IllegalArgumentException("refresh tenant mismatch");
            }
            Queue<RefreshCredential> queued = refreshResponses.get(presentedRefreshToken);
            RefreshCredential response = queued == null ? null : queued.poll();
            if (queued != null && queued.isEmpty()) {
                refreshResponses.remove(presentedRefreshToken, queued);
            }
            String accessToken = response == null ? account.accessToken() : response.accessToken();
            String refreshToken = response == null ? account.refreshToken() : response.refreshToken();
            String userId = response == null ? account.userId() : response.userId();
            ObjectNode credential = JSON.createObjectNode()
                    .put("accessToken", accessToken)
                    .put("refreshToken", refreshToken)
                    .put("userId", userId)
                    .put("expiresTime", 9_999_999_999L);
            return success(credential);
        }

        private String logout(RecordedRequest request) {
            requireRequest(request, "POST");
            Account account = accountByAccessToken(bearer(request));
            if (!account.tenantId().equals(request.header("tenant-id"))) {
                throw new IllegalArgumentException("logout tenant mismatch");
            }
            return success(JSON.getNodeFactory().booleanNode(true));
        }

        private static void requireRequest(RecordedRequest request, String method) {
            if (!method.equals(request.method()) || !"pc".equals(request.header("X-Platform-Type"))) {
                throw new IllegalArgumentException("unexpected fake OA request contract");
            }
        }

        private static String success(JsonNode data) {
            ObjectNode response = JSON.createObjectNode().put("code", 0).put("msg", "");
            response.set("data", data);
            return encode(response);
        }

        private static String encode(JsonNode response) {
            try {
                return JSON.writeValueAsString(response);
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to encode fake OA response", failure);
            }
        }

        private Account account(String account) {
            return Optional.ofNullable(accounts.get(account))
                    .orElseThrow(() -> new IllegalArgumentException("unknown fake OA account"));
        }

        private Account accountByAccessToken(String token) {
            return Optional.ofNullable(accessOwners.get(token))
                    .orElseThrow(() -> new IllegalArgumentException("unknown fake OA access token"));
        }

        private Account accountByRefreshToken(String token) {
            return Optional.ofNullable(refreshOwners.get(token))
                    .orElseThrow(() -> new IllegalArgumentException("unknown fake OA refresh token"));
        }

        private static String bearer(RecordedRequest request) {
            String value = request.header("Authorization");
            if (value == null || !value.startsWith("Bearer ")) {
                throw new IllegalArgumentException("missing bearer");
            }
            return value.substring("Bearer ".length());
        }

        private static String queryParameter(String query, String name) {
            if (query == null) {
                throw new IllegalArgumentException("missing query");
            }
            return Arrays.stream(query.split("&"))
                    .map(value -> value.split("=", 2))
                    .filter(value -> URLDecoder.decode(value[0], StandardCharsets.UTF_8).equals(name))
                    .map(value -> value.length == 2
                            ? URLDecoder.decode(value[1], StandardCharsets.UTF_8)
                            : "")
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("missing query parameter"));
        }

        private static void write(HttpExchange exchange, int status, String response) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            barriers.forEach(RequestBarrier::release);
            nextBarriers.clear();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    static final class RequestBarrier {
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void awaitArrival() {
            try {
                assertThat(arrived.await(8, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for fake OA request", failure);
            }
        }

        void release() {
            released.countDown();
        }

        void block() {
            arrived.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release fake OA request");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted fake OA request", failure);
            }
        }
    }

    static final class RealWebSocketRpcSession implements AutoCloseable {
        private final ObjectMapper json;
        private final WebSocketSession session;
        private final List<String> inbound;
        private final List<String> outbound;
        private final AtomicLong nextId = new AtomicLong();

        private RealWebSocketRpcSession(
                ObjectMapper json,
                WebSocketSession session,
                List<String> inbound,
                List<String> outbound) {
            this.json = json;
            this.session = session;
            this.inbound = inbound;
            this.outbound = outbound;
        }

        static RealWebSocketRpcSession connect(
                ObjectMapper json,
                int port,
                String desktopToken,
                String desktopInstanceId,
                String desktopSessionId) throws Exception {
            List<String> inbound = new CopyOnWriteArrayList<>();
            List<String> outbound = new CopyOnWriteArrayList<>();
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + desktopToken);
            headers.set("X-Desktop-Instance-Id", desktopInstanceId);
            headers.set("X-Desktop-Session-Id", desktopSessionId);
            headers.setOrigin("http://127.0.0.1");
            WebSocketSession session = new StandardWebSocketClient().execute(
                    new TextWebSocketHandler() {
                        @Override
                        protected void handleTextMessage(WebSocketSession ignored, TextMessage message) {
                            inbound.add(message.getPayload());
                        }
                    },
                    headers,
                    URI.create("ws://127.0.0.1:" + port + "/ws/agent")).get(8, TimeUnit.SECONDS);
            return new RealWebSocketRpcSession(json, session, inbound, outbound);
        }

        JsonNode request(String method, ObjectNode params) throws Exception {
            long id = nextId.incrementAndGet();
            ObjectNode request = json.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("method", method);
            request.set("params", params);
            String payload = json.writeValueAsString(request);
            outbound.add(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(parsedInbound()).anyMatch(message ->
                            !message.has("method") && message.path("id").asLong() == id));
            return parsedInbound().stream()
                    .filter(message -> !message.has("method") && message.path("id").asLong() == id)
                    .findFirst()
                    .orElseThrow();
        }

        JsonNode result(String method, ObjectNode params) throws Exception {
            JsonNode response = request(method, params);
            assertThat(response.path("error").isMissingNode()).as(response.toString()).isTrue();
            return response.path("result");
        }

        List<JsonNode> notifications(String method) {
            return parsedInbound().stream()
                    .filter(message -> method.equals(message.path("method").asText()))
                    .toList();
        }

        List<String> inboundFrames() {
            return List.copyOf(inbound);
        }

        List<String> outboundFrames() {
            return List.copyOf(outbound);
        }

        private List<JsonNode> parsedInbound() {
            return inbound.stream().map(this::parse).toList();
        }

        private JsonNode parse(String value) {
            try {
                return json.readTree(value);
            } catch (IOException failure) {
                throw new IllegalStateException("Invalid JSON from local business WebSocket", failure);
            }
        }

        @Override
        public void close() throws Exception {
            session.close();
        }
    }

    static void deleteTree(java.nio.file.Path root) {
        java.nio.file.Path normalized = root.toAbsolutePath().normalize();
        java.nio.file.Path targetRoot = java.nio.file.Path.of("target").toAbsolutePath().normalize();
        if (!normalized.startsWith(targetRoot) || !java.nio.file.Files.exists(normalized)) {
            return;
        }
        try (var paths = java.nio.file.Files.walk(normalized)) {
            paths.sorted(Comparator.comparingInt(java.nio.file.Path::getNameCount).reversed())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            path.toFile().deleteOnExit();
                        }
                    });
        } catch (IOException ignored) {
            normalized.toFile().deleteOnExit();
        }
    }
}
