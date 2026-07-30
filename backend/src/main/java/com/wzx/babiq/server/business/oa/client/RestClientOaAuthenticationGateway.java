package com.wzx.babiq.server.business.oa.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.business.oa.client.dto.OaAuthDtos;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;

public final class RestClientOaAuthenticationGateway implements OaAuthenticationGateway {
    private final BusinessOaProperties properties;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    public RestClientOaAuthenticationGateway(BusinessOaProperties properties) {
        this.properties = properties;
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs())).build();
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.requestTimeoutMs()));
        this.client = RestClient.builder().requestFactory(requestFactory).baseUrl(properties.endpointBase()).build();
    }
    @Override public List<OaAuthDtos.OaTenantCandidate> findTenantCandidates(String account) {
        JsonNode data = call(HttpMethod.GET,
                "/system/auth/get-users-by-mobile?mobile={account}",
                Map.of("account", account), null, null, null);
        if (!data.isArray()) protocol();
        List<OaAuthDtos.OaTenantCandidate> result = new ArrayList<>();
        for (JsonNode n : data) result.add(candidate(n, account));
        if (result.isEmpty()) throw new OaAuthenticationException(OaAuthenticationError.ACCOUNT_NOT_FOUND);
        if (result.stream().map(c -> c.userId() + "\u0000" + c.tenantId()).distinct().count() != result.size()) protocol();
        return result;
    }
    @Override public OaAuthDtos.OaCredential login(OaAuthDtos.OaTenantCandidate candidate, char[] password) {
        String encoded = OaPasswordEncoder.encode(password);
        String account = candidate.account() == null || candidate.account().isBlank() ? candidate.userId() : candidate.account();
        Map<String,Object> body = Map.of("mobileOrEmail", account, "password", encoded, "platformId", properties.platformId(), "tenantId", candidate.tenantId());
        return credential(call(HttpMethod.POST, "/system/auth/login", body, null, null));
    }
    @Override public OaAuthDtos.OaCredential refresh(String tenantId, char[] refreshToken) {
        String token = new String(refreshToken); Arrays.fill(refreshToken, '\0');
        return credential(call(HttpMethod.POST, "/system/auth/refresh-token", Map.of("refreshToken", token), tenantId, null));
    }
    @Override public OaAuthDtos.OaPermissionSnapshot loadPermissions(String tenantId, char[] accessToken) {
        String token = new String(accessToken); Arrays.fill(accessToken, '\0');
        JsonNode data = call(HttpMethod.GET, "/system/auth/get-permission-info?platformId=" + properties.platformId(), null, tenantId, token);
        JsonNode user = data.get("user"); if (user == null || !user.isObject()) protocol();
        String uid = scalar(user.get("id"));
        String name = scalarNullable(user.get("name"));
        if (name == null) name = scalarNullable(user.get("nickname"));
        return new OaAuthDtos.OaPermissionSnapshot(strings(data.get("permissions"), true), strings(data.get("roles"), false), uid, name, data.get("menus") != null && data.get("menus").isArray() ? mapper.convertValue(data.get("menus"), List.class) : protocolList());
    }
    @Override public void logout(String tenantId, char[] accessToken) {
        String token = new String(accessToken); Arrays.fill(accessToken, '\0'); call(HttpMethod.POST, "/system/auth/logout", null, tenantId, token);
    }
    private JsonNode call(HttpMethod method, String path) { return call(method,path,null,null,null); }
    private JsonNode call(HttpMethod method, String path, Object body, String tenant, String token) {
        return call(method, path, Map.of(), body, tenant, token);
    }
    private JsonNode call(HttpMethod method, String path, Map<String, ?> uriVariables,
                          Object body, String tenant, String token) {
        try {
            RestClient.RequestBodySpec req = client.method(method).uri(path, uriVariables)
                    .header("X-Platform-Type", "pc");
            if (tenant != null) req.header("tenant-id", tenant);
            if (token != null) req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            if (body != null && path.contains("refresh-token")) {
                req = req.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body("refreshToken=" + UriUtils.encode(String.valueOf(((Map<?,?>) body).get("refreshToken"))));
            } else if (body != null) {
                req = req.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            ResponseEntity<String> response = req.retrieve().toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful()) protocol();
            JsonNode root = mapper.readTree(response.getBody());
            String code = scalarNullable(root == null ? null : root.get("code"));
            if ("499".equals(code)) throw new OaAuthenticationException(OaAuthenticationError.AUTH_EXPIRED);
            if ("1002010000".equals(code)) throw new OaAuthenticationException(OaAuthenticationError.MEMBER_EXPIRED);
            if (root == null || !"0".equals(code)) {
                String marker = (code + " " + scalarNullable(root == null ? null : root.get("msg"))).toLowerCase();
                if (marker.contains("password") || marker.contains("credential") || marker.contains("\u5bc6\u7801")) throw new OaAuthenticationException(OaAuthenticationError.INVALID_CREDENTIALS);
                if (marker.contains("account") || marker.contains("user") || marker.contains("mobile") || marker.contains("\u8d26\u53f7")) throw new OaAuthenticationException(OaAuthenticationError.ACCOUNT_NOT_FOUND);
                protocol();
            }
            JsonNode data = root.get("data"); if (data == null || data.isNull()) protocol(); return data;
        } catch (OaAuthenticationException e) { throw e; }
        catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 499) {
                throw new OaAuthenticationException(OaAuthenticationError.AUTH_EXPIRED);
            }
            throw new OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR);
        }
        catch (org.springframework.web.client.ResourceAccessException e) {
            if (hasTimeoutCause(e)) throw new OaAuthenticationException(OaAuthenticationError.REMOTE_TIMEOUT);
            throw new OaAuthenticationException(OaAuthenticationError.REMOTE_UNAVAILABLE);
        } catch (Exception e) { throw new OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR); }
    }
    private OaAuthDtos.OaTenantCandidate candidate(JsonNode n, String account) { if (!n.isObject()) protocol(); int platform = integer(n.get("platformId")); if (platform != properties.platformId()) protocol(); return new OaAuthDtos.OaTenantCandidate(scalar(n.get("userId")), scalar(n.get("tenantId")), platform, scalarNullable(n.get("tenantName")), integer(n.get("tenantEnterStatus")), scalarNullable(n.get("tenantEnterId")), account); }
    private OaAuthDtos.OaCredential credential(JsonNode n) { return new OaAuthDtos.OaCredential(scalar(n.get("accessToken")), scalar(n.get("refreshToken")), scalar(n.get("userId")), Long.parseLong(scalar(n.get("expiresTime")))); }
    private List<String> strings(JsonNode n, boolean filterBlank) { if (n == null || !n.isArray()) protocol(); List<String> r=new ArrayList<>(); for(JsonNode x:n){if(!x.isTextual()) protocol(); if(!filterBlank || !x.asText().isBlank())r.add(x.asText());} return r; }
    private String scalar(JsonNode n){String s=scalarNullable(n);if(s==null||s.isBlank())protocol();return s;}
    private String scalarNullable(JsonNode n){return n==null||(!n.isTextual()&&!n.isIntegralNumber())?null:n.asText();}
    private int integer(JsonNode n){try{return Integer.parseInt(scalar(n));}catch(Exception e){protocol();return 0;}}
    private List<Object> protocolList(){protocol();return List.of();}
    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) return true;
        }
        return false;
    }
    private static void protocol(){throw new OaAuthenticationException(OaAuthenticationError.REMOTE_PROTOCOL_ERROR);}
    private static final class UriUtils { static String encode(String s){return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);} }
}
