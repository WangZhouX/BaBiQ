package com.wzx.babiq.server.business.oa.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientOaWorkbenchGatewayTest {
    private RecordingServer server;

    @BeforeEach
    void start() throws IOException {
        server = RecordingServer.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void usesTheRealWorkbenchPathsAndStablePageParameters() {
        RestClientOaWorkbenchGateway gateway = gateway();
        OaWorkbenchDtos.NoticePage notices = gateway.notices("tenant-2", chars("access"), 1, 10);
        OaWorkbenchDtos.PageResult cases = gateway.page(
                new OaWorkbenchDtos.PageQuery("CASE", 1007, "ALL", null, null, 1, 20, "1"),
                "tenant-2", chars("access"));

        assertThat(notices.total()).isEqualTo(2);
        assertThat(notices.items()).hasSize(1);
        assertThat(cases.items()).hasSize(1);
        assertThat(server.requests.get(0).path()).isEqualTo("/law-api/system/notice-push/page");
        assertThat(server.requests.get(0).query()).contains("pageNo=1", "pageSize=10", "type=3", "displayStatus=1");
        assertThat(server.requests.get(1).path()).isEqualTo("/law-api/lawyer/home-config/summary/case-handling-page");
        assertThat(server.requests.get(1).query()).contains("pageNo=1", "pageSize=20", "status=1");
        assertThat(server.requests).allSatisfy(request -> {
            assertThat(request.header("X-Platform-Type")).isEqualTo("pc");
            assertThat(request.header("Authorization")).isEqualTo("Bearer access");
            assertThat(request.header("tenant-id")).isEqualTo("tenant-2");
        });
    }

    @Test
    void preserves_the_four_kind_page_query_contract() {
        RestClientOaWorkbenchGateway gateway = gateway();
        List<OaWorkbenchDtos.PageQuery> queries = List.of(
                new OaWorkbenchDtos.PageQuery("CASE", 1007, "TEAM", "team-9", "role-x", 7, 13, "2"),
                new OaWorkbenchDtos.PageQuery("APPOINTMENT", 1006, "TEAM", "team-9", "role-x", 7, 13, "2"),
                new OaWorkbenchDtos.PageQuery("COUNSELOR_SERVICE", 1003, "TEAM", "team-9", "role-x", 7, 13, "1"),
                new OaWorkbenchDtos.PageQuery("VISIT", 1004, "TEAM", "team-9", "role-x", 7, 13, "2"));

        List<OaWorkbenchDtos.PageResult> pages = queries.stream()
                .map(query -> gateway.page(query, "tenant-2", chars("access")))
                .toList();

        assertThat(server.requests).hasSize(4);
        assertPageRequest(server.requests.get(0),
                "/law-api/lawyer/home-config/summary/case-handling-page", "status", "2");
        assertPageRequest(server.requests.get(1),
                "/law-api/lawyer/home-config/summary/appointment-page", "consultMode", "2");
        assertPageRequest(server.requests.get(2),
                "/law-api/counselor/home-config/summary/counselor-service-page", "serviceStatus", "1");
        assertPageRequest(server.requests.get(3),
                "/law-api/counselor/home-config/summary/visiting-page", "visitObj", "2");
        assertThat(pages).allSatisfy(page -> {
            assertThat(page.pageNo()).isEqualTo(7);
            assertThat(page.pageSize()).isEqualTo(13);
        });
    }

    @Test
    void omits_kind_specific_filter_when_none_was_requested() {
        RestClientOaWorkbenchGateway gateway = gateway();
        List<OaWorkbenchDtos.PageQuery> queries = List.of(
                new OaWorkbenchDtos.PageQuery("CASE", 1007, "ALL", null, null, 7, 13, null),
                new OaWorkbenchDtos.PageQuery("APPOINTMENT", 1006, "ALL", null, null, 7, 13, null),
                new OaWorkbenchDtos.PageQuery("COUNSELOR_SERVICE", 1003, "ALL", null, null, 7, 13, null),
                new OaWorkbenchDtos.PageQuery("VISIT", 1004, "ALL", null, null, 7, 13, null));

        queries.forEach(query -> gateway.page(query, "tenant-2", chars("access")));

        assertThat(server.requests).hasSize(4);
        assertThat(server.requests).allSatisfy(request ->
                assertThat(queryParameters(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                        "pageNo", "7",
                        "pageSize", "13")));
    }

    @Test
    void maps_personal_and_all_scopes_without_team_fields() {
        RestClientOaWorkbenchGateway gateway = gateway();

        gateway.page(new OaWorkbenchDtos.PageQuery(
                "CASE", 1007, "PERSONAL", null, null, 3, 11, "1"),
                "tenant-2", chars("access"));
        gateway.page(new OaWorkbenchDtos.PageQuery(
                "CASE", 1007, "ALL", null, null, 5, 17, "2"),
                "tenant-2", chars("access"));

        assertThat(queryParameters(server.requests.get(0))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pageNo", "3",
                "pageSize", "11",
                "dataType", "0",
                "status", "1"));
        assertThat(queryParameters(server.requests.get(1))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pageNo", "5",
                "pageSize", "17",
                "status", "2"));
    }

    @Test
    void acceptsNumericAndStringIdsButOnlyCodeZeroIsSuccess() {
        RestClientOaWorkbenchGateway gateway = gateway();
        OaWorkbenchDtos.UserHomeInfo info = gateway.homeInfo("tenant-2", chars("access"));
        assertThat(info.userId()).isEqualTo("100");
        assertThat(info.tenantId()).isEqualTo("tenant-2");

        server.response.set("{\"code\":200,\"msg\":\"ok\",\"data\":{}}");
        assertThatThrownBy(() -> gateway.homeInfo("tenant-2", chars("access")))
                .isInstanceOf(OaWorkbenchException.class)
                .hasMessage("REMOTE_PROTOCOL_ERROR");
    }

    @Test
    void acceptsRealScheduleArrayResponsesAndEmitsExactMonthDayTeamParameters() {
        RestClientOaWorkbenchGateway gateway = gateway();
        server.response.set("""
                {"code":0,"msg":"","data":[{"dateTime":"2026-07-29","count":2}]}
                """);

        Object counts = gateway.scheduleCount(
                "tenant-2", chars("access"), "2026-07", "TEAM", "team-9", true);

        assertThat(counts).isInstanceOf(List.class);
        assertThat(queryParameters(server.requests.getFirst())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "dateTime", "2026-07",
                "dataType", "1",
                "teamId", "team-9",
                "isOneself", "1"));

        server.response.set("""
                {"code":0,"msg":"","data":[{"id":"schedule-1","schTitle":"庭审"}]}
                """);
        Object schedules = gateway.scheduleDay(
                "tenant-2", chars("access"), "2026-07-29", "TEAM", "team-9", false, "type-1");

        assertThat(schedules).isInstanceOf(List.class);
        assertThat(queryParameters(server.requests.getLast())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "dateTime", "2026-07-29",
                "dataType", "1",
                "teamId", "team-9",
                "isOneself", "0",
                "typeId", "type-1"));
    }

    @Test
    void acceptsRealScheduleCreateScalarNumericAndStringIds() {
        RestClientOaWorkbenchGateway gateway = gateway();
        server.response.set("{\"code\":0,\"msg\":\"\",\"data\":123}");
        assertThat(gateway.createSchedule("tenant-2", chars("access"), Map.of("schTitle", "庭审")))
                .containsEntry("id", "123");

        server.response.set("{\"code\":0,\"msg\":\"\",\"data\":\"schedule-2\"}");
        assertThat(gateway.createSchedule("tenant-2", chars("access"), Map.of("schTitle", "会见")))
                .containsEntry("id", "schedule-2");
    }

    @Test
    void sendsScheduleCompletionAndActivationAsPutQueryParametersWithoutJsonBody() {
        RestClientOaWorkbenchGateway gateway = gateway();
        server.response.set("{\"code\":0,\"msg\":\"\",\"data\":true}");

        assertThat(gateway.setScheduleCompletion(
                "tenant-2", chars("access"), "schedule-1", true)).isTrue();
        assertThat(gateway.setScheduleCompletion(
                "tenant-2", chars("access"), "schedule-2", false)).isTrue();

        assertThat(server.requests).hasSize(2);
        assertThat(server.requests.get(0).method()).isEqualTo("PUT");
        assertThat(server.requests.get(0).path()).isEqualTo("/law-api/lawyer/law-schedule/complete");
        assertThat(queryParameters(server.requests.get(0))).containsExactly(Map.entry("id", "schedule-1"));
        assertThat(server.requests.get(0).body()).isEmpty();
        assertThat(server.requests.get(1).method()).isEqualTo("PUT");
        assertThat(server.requests.get(1).path()).isEqualTo("/law-api/lawyer/law-schedule/activate");
        assertThat(queryParameters(server.requests.get(1))).containsExactly(Map.entry("id", "schedule-2"));
        assertThat(server.requests.get(1).body()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":0,\"msg\":\"\",\"data\":[]}",
            "{\"code\":0,\"msg\":\"\",\"data\":{\"total\":1,\"list\":\"not-a-list\"}}",
            "{\"code\":0,\"msg\":\"\",\"data\":{\"total\":1,\"list\":[null]}}"
    })
    void maps_malformed_page_data_list_and_null_rows_to_remote_protocol_error(String response) {
        RestClientOaWorkbenchGateway gateway = gateway();
        OaWorkbenchDtos.PageQuery query = new OaWorkbenchDtos.PageQuery(
                "CASE", 1007, "ALL", null, null, 1, 20, null);
        server.response.set(response);

        assertThatThrownBy(() -> gateway.page(query, "tenant-2", chars("access")))
                .isInstanceOf(OaWorkbenchException.class)
                .hasMessage("REMOTE_PROTOCOL_ERROR");
    }

    @Test
    void does_not_destroy_caller_owned_token_array_after_a_request() {
        RestClientOaWorkbenchGateway gateway = gateway();
        char[] token = chars("access");

        gateway.homeInfo("tenant-2", token);

        assertThat(token).containsExactly('a', 'c', 'c', 'e', 's', 's');
    }

    @Test
    void mapsHttpAuthenticationFailuresAndKeepsCallerTokenOwned() {
        RestClientOaWorkbenchGateway gateway = gateway();
        char[] token = chars("access");

        server.responseStatus = 401;
        assertThatThrownBy(() -> gateway.notices("tenant-2", token, 1, 10))
                .isInstanceOf(com.wzx.babiq.server.business.oa.session.OaRemoteRequestException.class)
                .satisfies(error -> assertThat(((com.wzx.babiq.server.business.oa.session.OaRemoteRequestException) error)
                        .statusCode()).isEqualTo(401));
        assertThat(token).containsExactly("access".toCharArray());

        server.responseStatus = 499;
        assertThatThrownBy(() -> gateway.notices("tenant-2", token, 1, 10))
                .isInstanceOf(com.wzx.babiq.server.business.oa.session.OaRemoteRequestException.class)
                .satisfies(error -> assertThat(((com.wzx.babiq.server.business.oa.session.OaRemoteRequestException) error)
                        .statusCode()).isEqualTo(499));
        assertThat(token).containsExactly("access".toCharArray());
    }

    @Test
    void mapsHttp200TerminalBusinessCodesInsteadOfCollapsingThemIntoProtocolErrors() {
        RestClientOaWorkbenchGateway gateway = gateway();

        server.response.set("{\"code\":499,\"msg\":\"login expired\",\"data\":null}");
        assertThatThrownBy(() -> gateway.notices("tenant-2", chars("access"), 1, 10))
                .isInstanceOfSatisfying(OaRemoteRequestException.class,
                        failure -> assertThat(failure.statusCode()).isEqualTo(499));

        server.response.set("{\"code\":1002010000,\"msg\":\"member expired\",\"data\":null}");
        assertThatThrownBy(() -> gateway.notices("tenant-2", chars("access"), 1, 10))
                .isInstanceOfSatisfying(OaRemoteRequestException.class,
                        failure -> assertThat(failure.statusCode()).isEqualTo(1002010000));
    }

    @Test
    void acceptsHomeInfoNameWhenNicknameIsAbsent() {
        server.homeInfoUsesName = true;
        OaWorkbenchDtos.UserHomeInfo info = gateway().homeInfo("tenant-2", chars("access"));

        assertThat(info.nickname()).isEqualTo("Lawyer Name");
    }

    @Test
    void classifiesConnectionTimeoutAndUnavailabilityWithoutRemoteDetails() throws IOException {
        server.delayMillis = 500;
        RestClientOaWorkbenchGateway timeoutGateway = new RestClientOaWorkbenchGateway(new BusinessOaProperties(
                server.baseUrl(), "/law-api", 2, 50, true));

        assertThatThrownBy(() -> timeoutGateway.notices("tenant-2", chars("access"), 1, 10))
                .isInstanceOf(OaWorkbenchException.class)
                .hasMessage("REMOTE_TIMEOUT");

        server.stop();
        RestClientOaWorkbenchGateway unavailableGateway = gateway();
        assertThatThrownBy(() -> unavailableGateway.notices("tenant-2", chars("access"), 1, 10))
                .isInstanceOf(OaWorkbenchException.class)
                .hasMessage("REMOTE_UNAVAILABLE");
    }

    @Test
    void classifiesNonIdempotentWriteTransportFailureAsOutcomeUnknown() {
        RestClientOaWorkbenchGateway gateway = gateway();
        server.stop();

        assertThatThrownBy(() -> gateway.createSchedule("tenant-2", chars("access"),
                java.util.Map.of("schTitle", "test")))
                .isInstanceOf(OaRemoteRequestException.class)
                .satisfies(error -> {
                    OaRemoteRequestException remote = (OaRemoteRequestException) error;
                    assertThat(remote.ambiguousAfterSend()).isTrue();
                    assertThat(remote.authenticationExpired()).isFalse();
                });
    }

    @Test
    void fetches_only_same_origin_trusted_resources_with_auth_and_bounded_media_type() {
        RestClientOaWorkbenchGateway gateway = gateway();

        OaWorkbenchGateway.RemoteResource resource = gateway.fetchResource(
                "tenant-2", chars("access"), server.baseUrl() + "/avatar.png");

        assertThat(resource.mediaType()).isEqualTo("image/png");
        assertThat(resource.bytes()).containsExactly(1, 2, 3);
        RecordingServer.Request request = server.requests.getLast();
        assertThat(request.path()).isEqualTo("/avatar.png");
        assertThat(request.header("Authorization")).isEqualTo("Bearer access");
        assertThat(request.header("tenant-id")).isEqualTo("tenant-2");
        assertThatThrownBy(() -> gateway.fetchResource(
                "tenant-2", chars("access"), "https://attacker.example/avatar.png"))
                .isInstanceOf(OaWorkbenchException.class)
                .hasMessage("REMOTE_PROTOCOL_ERROR");
    }

    private RestClientOaWorkbenchGateway gateway() {
        return new RestClientOaWorkbenchGateway(new BusinessOaProperties(
                server.baseUrl(), "/law-api", 2, 3_000, true));
    }

    private static char[] chars(String value) {
        return value.toCharArray();
    }

    private static void assertPageRequest(RecordingServer.Request request, String expectedPath,
                                          String filterName, String filterValue) {
        assertThat(request.path()).isEqualTo(expectedPath);
        assertThat(queryParameters(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pageNo", "7",
                "pageSize", "13",
                "dataType", "1",
                "teamId", "team-9",
                "dataRoleCodes", "role-x",
                filterName, filterValue));
        assertThat(queryParameters(request)).doesNotContainKey("roleCode");
    }

    private static Map<String, String> queryParameters(RecordingServer.Request request) {
        return UriComponentsBuilder.fromUriString("http://localhost/?" + request.query())
                .build()
                .getQueryParams()
                .toSingleValueMap();
    }

    private static final class RecordingServer {
        private static final String DEFAULT_RESPONSE =
                "{\"code\":0,\"msg\":\"\",\"data\":{\"total\":2,\"list\":[{\"id\":1,\"title\":\"公告\"}]}}";
        private final HttpServer server;
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private volatile int responseStatus = 200;
        private volatile boolean homeInfoUsesName;
        private volatile long delayMillis;
        private final AtomicReference<String> response = new AtomicReference<>(DEFAULT_RESPONSE);

        private RecordingServer(HttpServer server) {
            this.server = server;
        }

        static RecordingServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RecordingServer recording = new RecordingServer(server);
            server.createContext("/", recording::handle);
            server.start();
            return recording;
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), new String(requestBody, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            entry -> entry.getKey(), entry -> entry.getValue().stream().findFirst().orElse(""),
                            (left, right) -> left))));
            if (exchange.getRequestURI().getPath().endsWith("/avatar.png")) {
                byte[] bytes = {1, 2, 3};
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            String configuredResponse = response.get();
            String body = configuredResponse;
            if (exchange.getRequestURI().getPath().endsWith("home-info")
                    && DEFAULT_RESPONSE.equals(configuredResponse)) {
                body = "{\"code\":0,\"msg\":\"\",\"data\":{\"userId\":100,\"nickname\":\"律师\",\"tenantId\":\"tenant-2\"}}";
            } else if (exchange.getRequestURI().getPath().endsWith("case-handling-page")
                    && DEFAULT_RESPONSE.equals(configuredResponse)) {
                body = "{\"code\":0,\"msg\":\"\",\"data\":{\"total\":1,\"list\":[{\"id\":\"case-1\",\"applicationNumber\":\"A-1\",\"categoriesName\":\"民事\"}]}}";
            }
            if (homeInfoUsesName && exchange.getRequestURI().getPath().endsWith("home-info")) {
                body = "{\"code\":0,\"msg\":\"\",\"data\":{\"userId\":100,\"name\":\"Lawyer Name\",\"tenantId\":\"tenant-2\"}}";
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private record Request(String method, String path, String query, String body,
                               java.util.Map<String, String> headers) {
            String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(java.util.Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }
        }
    }
}
