package com.wzx.babiq.server.business.oa.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientOaAuthenticationGatewayTest {
    private RecordingOaServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = RecordingOaServer.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void sendsTheStableLawApiContractAndDoesNotExposeRawPasswordOrTokens() {
        RestClientOaAuthenticationGateway gateway = gateway();
        OaAuthDtos.OaTenantCandidate candidate = gateway.findTenantCandidates("13800138000").get(0);

        OaAuthDtos.OaCredential credential = gateway.login(candidate, "Abcdef12".toCharArray());
        OaAuthDtos.OaCredential refreshed = gateway.refresh(candidate.tenantId(), "refresh-1".toCharArray());
        OaAuthDtos.OaPermissionSnapshot permissions = gateway.loadPermissions(
                candidate.tenantId(), "access-2".toCharArray());
        gateway.logout(candidate.tenantId(), "access-2".toCharArray());

        assertThat(candidate.userId()).isEqualTo("123");
        assertThat(candidate.tenantId()).isEqualTo("2");
        assertThat(credential.accessToken()).isEqualTo("access-1");
        assertThat(refreshed.accessToken()).isEqualTo("access-2");
        assertThat(permissions.permissions()).containsExactly("law:case:query");
        assertThat(server.requests).hasSize(5);
        assertThat(server.requests.get(0).path).isEqualTo("/law-api/system/auth/get-users-by-mobile");
        assertThat(server.requests.get(0).query).isEqualTo("mobile=13800138000");
        assertThat(server.requests.get(1).body).contains("\"password\":\"6d93c260d711cdb51207c420279ae936\"");
        assertThat(server.requests.get(1).body).doesNotContain("Abcdef12");
        assertThat(server.requests.get(2).contentType()).contains("application/x-www-form-urlencoded");
        assertThat(server.requests.get(2).body).isEqualTo("refreshToken=refresh-1");
        assertThat(server.requests.get(2).query).isNull();
        assertThat(server.requests.get(2).header("tenant-id")).isEqualTo("2");
        assertThat(server.requests.get(3).header("Authorization")).isEqualTo("Bearer access-2");
        assertThat(server.requests.get(4).header("Authorization")).isEqualTo("Bearer access-2");
        assertThat(server.requests).allSatisfy(request ->
                assertThat(request.header("X-Platform-Type")).isEqualTo("pc"));
        assertThat(credential.toString()).doesNotContain("access-1", "refresh-1");
    }

    @Test
    void encodesEmailAccountQueryExactlyOnce() {
        RestClientOaAuthenticationGateway gateway = gateway();

        gateway.findTenantCandidates("audit@example.test");

        String rawQuery = server.requests.getFirst().query;
        assertThat(rawQuery).doesNotContain("%25");
        assertThat(java.net.URLDecoder.decode(rawQuery, StandardCharsets.UTF_8))
                .isEqualTo("mobile=audit@example.test");
    }

    @Test
    void treatsOnlyBusinessCodeZeroAsSuccessAndMapsEmptyCandidatesSafely() {
        server.response = "{\"code\":200,\"msg\":\"ok\",\"data\":[]}";
        RestClientOaAuthenticationGateway gateway = gateway();

        assertThatThrownBy(() -> gateway.findTenantCandidates("13800138000"))
                .isInstanceOf(OaAuthenticationException.class)
                .hasMessage("REMOTE_PROTOCOL_ERROR");

        server.response = "{\"code\":0,\"msg\":\"\",\"data\":[]}";
        assertThatThrownBy(() -> gateway.findTenantCandidates("13800138000"))
                .isInstanceOf(OaAuthenticationException.class)
                .hasMessage("ACCOUNT_NOT_FOUND");
    }

    @Test
    void mapsRefreshTerminalBusinessCodesWithoutLeakingRemoteMessages() {
        RestClientOaAuthenticationGateway gateway = gateway();

        server.refreshResponse = "{\"code\":499,\"msg\":\"secret-auth-detail\",\"data\":null}";
        assertThatThrownBy(() -> gateway.refresh("2", "refresh-1".toCharArray()))
                .isInstanceOf(OaAuthenticationException.class)
                .hasMessage("AUTH_EXPIRED")
                .hasMessageNotContaining("secret-auth-detail");

        server.refreshResponse = "{\"code\":1002010000,\"msg\":\"secret-member-detail\",\"data\":null}";
        assertThatThrownBy(() -> gateway.refresh("2", "refresh-1".toCharArray()))
                .isInstanceOf(OaAuthenticationException.class)
                .hasMessage("MEMBER_EXPIRED")
                .hasMessageNotContaining("secret-member-detail");
    }

    @Test
    void rejectsRedirectsAndNeverIncludesRemoteBodyInFailures() throws IOException {
        server.redirect = true;
        RestClientOaAuthenticationGateway gateway = gateway();

        assertThatThrownBy(() -> gateway.findTenantCandidates("13800138000"))
                .isInstanceOf(OaAuthenticationException.class)
                .hasMessage("REMOTE_PROTOCOL_ERROR")
                .hasMessageNotContaining("remote-body-secret");
    }

    private RestClientOaAuthenticationGateway gateway() {
        return new RestClientOaAuthenticationGateway(new BusinessOaProperties(
                server.baseUrl(), "/law-api", 2, 3_000, true));
    }

    private static final class RecordingOaServer {
        private final HttpServer server;
        private final List<Request> requests = new ArrayList<>();
        private String response = "{\"code\":0,\"msg\":\"\",\"data\":[{\"userId\":123,\"tenantId\":2,\"platformId\":2,\"tenantName\":\"Personal\",\"tenantEnterStatus\":1}]}";
        private String refreshResponse;
        private boolean redirect;

        private RecordingOaServer(HttpServer server) {
            this.server = server;
        }

        static RecordingOaServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            RecordingOaServer recording = new RecordingOaServer(server);
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
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request request = new Request(exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(), body,
                    exchange.getRequestHeaders().entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    entry -> entry.getKey(), entry -> entry.getValue().stream().findFirst().orElse(""),
                                    (left, right) -> left)));
            requests.add(request);
            if (redirect) {
                exchange.getResponseHeaders().set("Location", baseUrl() + "/secret");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            String responseBody = response;
            if (request.path.endsWith("/login")) {
                responseBody = "{\"code\":0,\"msg\":\"\",\"data\":{\"accessToken\":\"access-1\",\"refreshToken\":\"refresh-1\",\"userId\":123,\"expiresTime\":123}}";
            } else if (request.path.endsWith("/refresh-token")) {
                responseBody = refreshResponse == null
                        ? "{\"code\":0,\"msg\":\"\",\"data\":{\"accessToken\":\"access-2\",\"refreshToken\":\"refresh-2\",\"userId\":123,\"expiresTime\":456}}"
                        : refreshResponse;
            } else if (request.path.endsWith("/get-permission-info")) {
                responseBody = "{\"code\":0,\"msg\":\"\",\"data\":{\"permissions\":[\"law:case:query\",\"\"],\"roles\":[\"admin\"],\"user\":{\"id\":123,\"nickname\":\"User\"},\"menus\":[]}}";
            } else if (request.path.endsWith("/logout")) {
                responseBody = "{\"code\":0,\"msg\":\"\",\"data\":true}";
            }
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private record Request(String path, String query, String body, java.util.Map<String, String> headers) {
            String header(String name) {
                return headers.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(java.util.Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
            }

            String contentType() {
                return Optional.ofNullable(header("Content-Type")).orElse("");
            }
        }
    }
}
