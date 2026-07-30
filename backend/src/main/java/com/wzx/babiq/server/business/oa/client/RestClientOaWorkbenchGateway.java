package com.wzx.babiq.server.business.oa.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.business.oa.client.dto.OaWorkbenchDtos;
import com.wzx.babiq.server.business.oa.config.BusinessOaProperties;
import com.wzx.babiq.server.business.oa.session.OaRemoteRequestException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** RestClient adapter for the real lawyer OA workbench endpoints. */
public final class RestClientOaWorkbenchGateway implements OaWorkbenchGateway {
    private static final int MAX_RESOURCE_BYTES = 20_000_000;
    private static final Set<String> SAFE_RESOURCE_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "application/pdf",
            "video/mp4", "video/x-msvideo", "video/quicktime", "video/x-matroska", "video/webm");
    private final RestClient client;
    private final BusinessOaProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public RestClientOaWorkbenchGateway(BusinessOaProperties properties) {
        this.properties = properties;
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.requestTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.requestTimeoutMs()));
        this.client = RestClient.builder().requestFactory(requestFactory).baseUrl(properties.endpointBase()).build();
    }

    @Override public OaWorkbenchDtos.NoticePage notices(String tenantId, char[] accessToken, int pageNo, int pageSize) {
        JsonNode data = get("/system/notice-push/page", tenantId, accessToken,
                Map.of("pageNo", pageNo, "pageSize", pageSize, "type", 3, "displayStatus", 1));
        return pageFrom(data);
    }

    @Override public List<Map<String, Object>> shortcuts(String tenantId, char[] token) {
        return listData(get("/lawyer/home-config/list-shortcut", tenantId, token, Map.of()));
    }

    @Override public List<Map<String, Object>> summary(String tenantId, char[] token) {
        return listData(get("/lawyer/home-config/summary", tenantId, token, Map.of()));
    }

    @Override public OaWorkbenchDtos.UserHomeInfo homeInfo(String tenantId, char[] token) {
        JsonNode data = get("/system/user/home-info", tenantId, token, Map.of());
        Map<String, Object> values = objectMap(data);
        String nickname = text(values.get("nickname"));
        if (nickname == null || nickname.isBlank()) nickname = text(values.get("name"));
        return new OaWorkbenchDtos.UserHomeInfo(identifier(values.get("userId")), identifier(values.get("tenantId")),
                nickname, text(values.get("avatar")), values);
    }

    @Override public List<Map<String, Object>> teams(String tenantId, char[] token) {
        return listData(get("/system/team/list", tenantId, token, Map.of("status", 1, "type", 5)));
    }

    @Override public List<Map<String, Object>> teamRoles(String tenantId, char[] token, String teamId, String kind) {
        int module = switch (kind) {
            case "CASE" -> 1007;
            case "APPOINTMENT" -> 1006;
            case "COUNSELOR_SERVICE" -> 1003;
            case "VISIT" -> 1004;
            default -> throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
        };
        return listData(get("/system/team-data-role/list-by-team-and-module", tenantId, token,
                Map.of("teamId", teamId, "moduleId", module)));
    }

    @Override public Object scheduleCount(String tenantId, char[] token) {
        return scheduleData(get("/lawyer/law-schedule/list-count", tenantId, token, Map.of()));
    }

    @Override public Object scheduleDay(String tenantId, char[] token) {
        return scheduleData(get("/lawyer/law-schedule/list-day", tenantId, token, Map.of()));
    }

    @Override public boolean updateSort(String tenantId, char[] token, int configType, List<String> ids) {
        return Boolean.TRUE.equals(put("/lawyer/home-config/update-sort", tenantId, token,
                Map.of("configType", configType, "ids", ids)));
    }

    @Override public Object scheduleCount(String tenantId, char[] token, String date,
                                          String scope, String teamId, boolean onlyMine) {
        return scheduleData(get("/lawyer/law-schedule/list-count", tenantId, token,
                scheduleParams(date, scope, teamId, onlyMine, null)));
    }

    @Override public Object scheduleDay(String tenantId, char[] token, String date,
                                        String scope, String teamId, boolean onlyMine, String typeId) {
        return scheduleData(get("/lawyer/law-schedule/list-day", tenantId, token,
                scheduleParams(date, scope, teamId, onlyMine, typeId)));
    }

    @Override public boolean setScheduleCompletion(String tenantId, char[] token, String scheduleId, boolean completed) {
        String path = completed ? "/lawyer/law-schedule/complete" : "/lawyer/law-schedule/activate";
        return Boolean.TRUE.equals(putWithParams(path, tenantId, token, Map.of("id", scheduleId)));
    }

    @Override public List<Map<String, Object>> scheduleTypes(String tenantId, char[] token, String scope, String teamId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dataType", "TEAM".equals(scope) ? 1 : 0);
        if (teamId != null) params.put("teamId", teamId);
        return listData(get("/lawyer/law-schedule-type/page", tenantId, token, params));
    }

    @Override public List<Map<String, Object>> scheduleMembers(String tenantId, char[] token, String teamId) {
        return listData(get("/system/team-member/normal-list-by-team", tenantId, token, Map.of("teamId", teamId)));
    }

    @Override public boolean isTeamLeaderOrAdmin(String tenantId, char[] token, String teamId) {
        JsonNode data = get("/system/team-member/check-leader-admin", tenantId, token, Map.of("teamId", teamId));
        if (!data.isBoolean()) throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
        return data.booleanValue();
    }

    @Override public List<Map<String, Object>> relationOptions(String tenantId, char[] token, String relationType,
                                                                 String keyword, String teamId, String parentId) {
        String path = switch (relationType) {
            case "CUSTOMER" -> "/lawyer/customer-management/option-list";
            case "CASE" -> "/lawyer/case-application-lawyer/option-list";
            case "VISIT" -> "/counselor/visiting-record/option-list";
            case "SERVICE" -> "/counselor/service-record/option-list";
            default -> throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
        };
        Map<String, Object> params = new LinkedHashMap<>();
        if (keyword != null) params.put("keyword", keyword);
        if (teamId != null) params.put("teamId", teamId);
        return listData(get(path, tenantId, token, params));
    }

    @Override public List<Map<String, Object>> serviceProjects(String tenantId, char[] token, String recordId, String keyword) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recordId", recordId);
        if (keyword != null) params.put("keyword", keyword);
        return listData(get("/counselor/service-record-project/option-list", tenantId, token, params));
    }

    @Override public Map<String, Object> createSchedule(String tenantId, char[] token, Map<String, Object> payload) {
        JsonNode data = post("/lawyer/law-schedule/create", tenantId, token, payload);
        if (data.isNumber() || data.isTextual()) {
            String id = data.asText();
            if (id.isBlank()) throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
            return Map.of("id", id);
        }
        return objectMap(data);
    }

    @Override
    public RemoteResource fetchResource(String tenantId, char[] token, String trustedUrl) {
        URI uri = trustedResourceUri(trustedUrl);
        try {
            return client.get().uri(uri)
                    .header("X-Platform-Type", "pc")
                    .header("tenant-id", tenantId)
                    .header("Authorization", "Bearer " + new String(token))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 401 || status == 499) {
                            throw OaRemoteRequestException.authenticationExpired(status);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new OaWorkbenchException("REMOTE_UNAVAILABLE");
                        }
                        String mediaType = response.getHeaders().getFirst("Content-Type");
                        if (mediaType != null && mediaType.contains(";")) {
                            mediaType = mediaType.substring(0, mediaType.indexOf(';'));
                        }
                        mediaType = mediaType == null ? "" : mediaType.strip().toLowerCase(java.util.Locale.ROOT);
                        long declaredLength = response.getHeaders().getContentLength();
                        if (!SAFE_RESOURCE_MEDIA_TYPES.contains(mediaType)
                                || declaredLength == 0 || declaredLength >= MAX_RESOURCE_BYTES) {
                            throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
                        }
                        try (var input = response.getBody()) {
                            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES);
                            if (bytes.length == 0 || bytes.length >= MAX_RESOURCE_BYTES || input.read() != -1
                                    || declaredLength > 0 && declaredLength != bytes.length) {
                                throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
                            }
                            return new RemoteResource(mediaType, bytes);
                        }
                    });
        } catch (OaRemoteRequestException | OaWorkbenchException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new OaWorkbenchException(hasTimeoutCause(exception) ? "REMOTE_TIMEOUT" : "REMOTE_UNAVAILABLE");
        } catch (Exception exception) {
            throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
        }
    }

    private URI trustedResourceUri(String trustedUrl) {
        try {
            URI base = URI.create(properties.baseUrl());
            URI candidate = URI.create(trustedUrl);
            if (!candidate.isAbsolute()) candidate = base.resolve(candidate);
            if (candidate.getRawUserInfo() != null || candidate.getRawFragment() != null
                    || !base.getScheme().equalsIgnoreCase(candidate.getScheme())
                    || !base.getHost().equalsIgnoreCase(candidate.getHost())
                    || effectivePort(base) != effectivePort(candidate)) {
                throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
            }
            return candidate.normalize();
        } catch (OaWorkbenchException exception) {
            throw exception;
        } catch (RuntimeException invalid) {
            throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
        }
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) return value.getPort();
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    @Override public OaWorkbenchDtos.PageResult page(OaWorkbenchDtos.PageQuery query, String tenantId, char[] token) {
        String path = switch (query.kind()) {
            case "CASE" -> "/lawyer/home-config/summary/case-handling-page";
            case "APPOINTMENT" -> "/lawyer/home-config/summary/appointment-page";
            case "COUNSELOR_SERVICE" -> "/counselor/home-config/summary/counselor-service-page";
            case "VISIT" -> "/counselor/home-config/summary/visiting-page";
            default -> throw new OaWorkbenchException("REMOTE_PROTOCOL_ERROR");
        };
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pageNo", query.pageNo());
        params.put("pageSize", query.pageSize());
        if ("TEAM".equals(query.scope())) params.put("dataType", 1);
        if ("PERSONAL".equals(query.scope())) params.put("dataType", 0);
        if (query.teamId() != null) params.put("teamId", query.teamId());
        if (query.roleCode() != null) params.put("dataRoleCodes", query.roleCode());
        if (query.filterValue() != null) {
            int filterValue = Integer.parseInt(query.filterValue());
            switch (query.kind()) {
                case "CASE" -> params.put("status", filterValue);
                case "APPOINTMENT" -> params.put("consultMode", filterValue);
                case "COUNSELOR_SERVICE" -> params.put("serviceStatus", filterValue);
                case "VISIT" -> params.put("visitObj", filterValue);
            }
        }
        return pageResultFrom(get(path, tenantId, token, params), query.pageNo(), query.pageSize());
    }

    private JsonNode get(String path, String tenantId, char[] token, Map<String, ?> params) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.endpointBase()).path(path)
                    .queryParams(org.springframework.util.CollectionUtils.toMultiValueMap(
                            params.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, e -> List.of(String.valueOf(e.getValue()))))))
                    .build().encode().toUri();
            String body = client.get().uri(uri).header("X-Platform-Type", "pc")
                    .header("tenant-id", tenantId).header("Authorization", "Bearer " + new String(token))
                    .retrieve().body(String.class);
            JsonNode root = mapper.readTree(body);
            requireSuccessfulEnvelope(root);
            JsonNode data = root.get("data");
            if (data == null || data.isNull()) throw new OaWorkbenchException();
            return data;
        } catch (OaRemoteRequestException | OaWorkbenchException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 499) throw OaRemoteRequestException.authenticationExpired(status);
            throw new OaWorkbenchException();
        } catch (ResourceAccessException exception) {
            throw new OaWorkbenchException(hasTimeoutCause(exception) ? "REMOTE_TIMEOUT" : "REMOTE_UNAVAILABLE");
        } catch (Exception exception) {
            throw new OaWorkbenchException();
        }
    }

    private Object put(String path, String tenantId, char[] token, Map<String, ?> body) {
        try {
            String response = client.put().uri(UriComponentsBuilder.fromUriString(properties.endpointBase()).path(path).build().toUri())
                    .header("X-Platform-Type", "pc").header("tenant-id", tenantId)
                    .header("Authorization", "Bearer " + new String(token)).body(body).retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            requireSuccessfulEnvelope(root);
            JsonNode data = root.get("data");
            return data == null || data.isNull() ? Boolean.TRUE : mapper.convertValue(data, Object.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 499)
                throw OaRemoteRequestException.authenticationExpired(exception.getStatusCode().value());
            throw new OaWorkbenchException();
        } catch (ResourceAccessException exception) {
            throw new OaWorkbenchException(hasTimeoutCause(exception) ? "REMOTE_TIMEOUT" : "REMOTE_UNAVAILABLE");
        } catch (OaRemoteRequestException | OaWorkbenchException exception) { throw exception;
        } catch (Exception exception) { throw new OaWorkbenchException(); }
    }

    private Object putWithParams(String path, String tenantId, char[] token, Map<String, ?> params) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(properties.endpointBase()).path(path)
                    .queryParams(org.springframework.util.CollectionUtils.toMultiValueMap(
                            params.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey, entry -> List.of(String.valueOf(entry.getValue()))))))
                    .build().encode().toUri();
            String response = client.put().uri(uri)
                    .header("X-Platform-Type", "pc").header("tenant-id", tenantId)
                    .header("Authorization", "Bearer " + new String(token))
                    .retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            requireSuccessfulEnvelope(root);
            JsonNode data = root.get("data");
            return data == null || data.isNull() ? Boolean.TRUE : mapper.convertValue(data, Object.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 499) {
                throw OaRemoteRequestException.authenticationExpired(exception.getStatusCode().value());
            }
            throw new OaWorkbenchException();
        } catch (ResourceAccessException exception) {
            throw new OaWorkbenchException(hasTimeoutCause(exception) ? "REMOTE_TIMEOUT" : "REMOTE_UNAVAILABLE");
        } catch (OaRemoteRequestException | OaWorkbenchException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new OaWorkbenchException();
        }
    }

    private JsonNode post(String path, String tenantId, char[] token, Map<String, ?> body) {
        try {
            String response = client.post().uri(UriComponentsBuilder.fromUriString(properties.endpointBase()).path(path).build().toUri())
                    .header("X-Platform-Type", "pc").header("tenant-id", tenantId)
                    .header("Authorization", "Bearer " + new String(token)).body(body).retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            requireSuccessfulEnvelope(root);
            if (root.get("data") == null) throw new OaWorkbenchException();
            return root.get("data");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 499)
                throw OaRemoteRequestException.authenticationExpired(exception.getStatusCode().value());
            throw new OaWorkbenchException();
        } catch (ResourceAccessException exception) {
            // A write may have reached OA before the transport failed; callers must reconcile the
            // result rather than replaying it automatically.
            throw OaRemoteRequestException.networkFailure(true);
        } catch (OaRemoteRequestException | OaWorkbenchException exception) { throw exception;
        } catch (Exception exception) { throw new OaWorkbenchException(); }
    }

    private static Map<String, Object> scheduleParams(String date, String scope, String teamId,
                                                        boolean onlyMine, String typeId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("dateTime", date);
        params.put("dataType", "TEAM".equals(scope) ? 1 : 0);
        params.put("isOneself", onlyMine ? 1 : 0);
        if (teamId != null) params.put("teamId", teamId);
        if (typeId != null) params.put("typeId", typeId);
        return params;
    }

    private OaWorkbenchDtos.NoticePage pageFrom(JsonNode data) {
        return protocol(() -> {
            Map<String, Object> object = objectMap(data);
            return new OaWorkbenchDtos.NoticePage(longValue(object.get("total")), intValue(object.get("pageNo"), 1),
                    intValue(object.get("pageSize"), 10), list(object.get("list")));
        });
    }
    private OaWorkbenchDtos.PageResult pageResultFrom(JsonNode data, int fallbackPageNo, int fallbackPageSize) {
        return protocol(() -> {
            Map<String, Object> object = objectMap(data);
            return new OaWorkbenchDtos.PageResult(longValue(object.get("total")),
                    intValue(object.get("pageNo"), fallbackPageNo),
                    intValue(object.get("pageSize"), fallbackPageSize), list(object.get("list")));
        });
    }
    private List<Map<String, Object>> listData(JsonNode data) {
        return protocol(() -> data.isArray() ? list(data) : list(objectMap(data).get("list")));
    }
    private Object scheduleData(JsonNode data) {
        return protocol(() -> data.isArray() ? list(data) : objectMap(data));
    }
    private List<Map<String, Object>> list(Object value) {
        if (value == null) return List.of();
        return protocol(() -> {
            List<Map<String, Object>> rows = mapper.convertValue(
                    value, new TypeReference<List<Map<String, Object>>>() {});
            if (rows == null || rows.stream().anyMatch(row -> row == null)) throw new OaWorkbenchException();
            return rows;
        });
    }
    private Map<String, Object> objectMap(JsonNode node) {
        return protocol(() -> {
            if (node == null || !node.isObject()) throw new OaWorkbenchException();
            Map<String, Object> object = mapper.convertValue(node, new TypeReference<>() {});
            if (object == null) throw new OaWorkbenchException();
            return object;
        });
    }
    private static <T> T protocol(Supplier<T> conversion) {
        try {
            return conversion.get();
        } catch (OaWorkbenchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OaWorkbenchException();
        }
    }
    private static String identifier(Object value) { return value == null ? null : String.valueOf(value); }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private static long longValue(Object value) { try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); } catch (Exception e) { return 0; } }

    private static boolean hasTimeoutCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) return true;
        }
        return false;
    }

    private static void requireSuccessfulEnvelope(JsonNode root) {
        if (root == null) throw new OaWorkbenchException();
        String code = root.path("code").asText();
        if ("499".equals(code)) throw OaRemoteRequestException.authenticationExpired(499);
        if ("1002010000".equals(code)) throw OaRemoteRequestException.membershipExpired(1002010000);
        if (!"0".equals(code)) throw new OaWorkbenchException();
    }
}
