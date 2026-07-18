package com.wzx.babiq.server.application.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Kotlin 业务桌面与 Java Agent 后端共享的应用层 JSON-RPC 契约。 */
public final class ApplicationProtocol {

    public static final String PROTOCOL_VERSION = "1.0";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    private ApplicationProtocol() {
    }

    /** 解析 JSON 文本为协议测试和适配层使用的树结构。 */
    public static JsonNode readTree(String value) {
        try {
            return JSON.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot parse application protocol JSON", exception);
        }
    }

    /** 生成可变 payload/result 节点。 */
    public static com.fasterxml.jackson.databind.node.ObjectNode objectNode() {
        return JSON.createObjectNode();
    }

    /** 将 JSON tree 转换成简单 Java 值。 */
    public static <T> T convertValue(JsonNode value, Class<T> type) {
        return JSON.convertValue(value, type);
    }

    /** 序列化协议消息后重新解析，返回实际 wire tree。 */
    public static JsonNode serializeTree(ProtocolMessage value) {
        try {
            return JSON.readTree(JSON.writeValueAsBytes(value));
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Cannot serialize application protocol message", exception);
        }
    }

    /** 按 JSON-RPC method 选择参数 record，避免用字段形状猜测目录和上下文消息。 */
    public static ProtocolMessage decode(JsonNode value) {
        if (value.has("method")) {
            String method = requiredText(value, "method");
            ApplicationEnvelope params = decodeParams(method, value.path("params"));
            if (value.has("id")) {
                return new Request(requiredText(value, "jsonrpc"), requiredLong(value, "id"), method, params);
            }
            return new Notification(requiredText(value, "jsonrpc"), method, params);
        }
        if (value.has("error")) {
            return JSON.convertValue(value, ErrorResponse.class);
        }
        if (value.has("result")) {
            return JSON.convertValue(value, SuccessResponse.class);
        }
        throw new IllegalArgumentException("Unsupported application protocol message");
    }

    /** 将已解析的协议 record 转回标准 JSON tree。 */
    public static JsonNode encode(ProtocolMessage value) {
        return serializeTree(value);
    }

    private static ApplicationEnvelope decodeParams(String method, JsonNode params) {
        ApplicationMethod applicationMethod = ApplicationMethod.fromWireName(method);
        Class<? extends ApplicationEnvelope> type = switch (applicationMethod) {
            case CATALOG_REGISTER, CATALOG_UPDATE, CONTEXT_PUBLISH -> ApplicationCatalogMessage.class;
            case IDENTITY_BIND, IDENTITY_UPDATE -> ApplicationIdentityMessage.class;
            default -> ApplicationActionMessage.class;
        };
        return JSON.convertValue(params, type);
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        if (fieldValue == null || !fieldValue.isTextual()) {
            throw new IllegalArgumentException("Missing textual field: " + field);
        }
        return fieldValue.textValue();
    }

    private static long requiredLong(JsonNode value, String field) {
        JsonNode fieldValue = value.get(field);
        if (fieldValue == null || !fieldValue.isIntegralNumber() || !fieldValue.canConvertToLong()) {
            throw new IllegalArgumentException("Missing integral field: " + field);
        }
        return fieldValue.longValue();
    }

    /** 业务桌面应用协议的 19 个固定 method。 */
    public enum ApplicationMethod {
        CATALOG_REGISTER("application/catalog/register"),
        CATALOG_UPDATE("application/catalog/update"),
        CONTEXT_PUBLISH("application/context/publish"),
        IDENTITY_BIND("application/identity/bind"),
        IDENTITY_UPDATE("application/identity/update"),
        ACTION_REQUEST("application/action/request"),
        ACTION_CANCEL("application/action/cancel"),
        ACTION_ACCEPTED("application/action/accepted"),
        ACTION_PREVIEWED("application/action/previewed"),
        ACTION_APPROVAL_REQUIRED("application/action/approval-required"),
        ACTION_RUNNING("application/action/running"),
        ACTION_COMPLETED("application/action/completed"),
        ACTION_FAILED("application/action/failed"),
        ACTION_REJECTED("application/action/rejected"),
        ACTION_CANCELED("application/action/canceled"),
        ACTION_EXPIRED("application/action/expired"),
        ACTION_OUTCOME_UNKNOWN("application/action/outcome-unknown"),
        ACTION_STATUS("application/action/status"),
        ACTION_RESULT_GET("application/action/result/get");

        private static final Set<String> WIRE_NAMES = Collections.unmodifiableSet(
                new LinkedHashSet<>(Arrays.stream(values()).map(ApplicationMethod::wireName).toList()));

        private final String wireName;

        ApplicationMethod(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Set<String> wireNames() {
            return WIRE_NAMES;
        }

        public static ApplicationMethod fromWireName(String wireName) {
            return Arrays.stream(values())
                    .filter(value -> value.wireName.equals(wireName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported application method"));
        }
    }

    public sealed interface ProtocolMessage permits Request, Notification, SuccessResponse, ErrorResponse {
        String jsonrpc();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(String jsonrpc, long id, String method, ApplicationEnvelope params)
            implements ProtocolMessage {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notification(String jsonrpc, String method, ApplicationEnvelope params)
            implements ProtocolMessage {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuccessResponse(String jsonrpc, long id, JsonNode result) implements ProtocolMessage {
        public SuccessResponse {
            result = result == null ? null : result.deepCopy();
        }

        @Override
        public JsonNode result() {
            return result == null ? null : result.deepCopy();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorResponse(String jsonrpc, Long id, Error error) implements ProtocolMessage {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(int code, String message, JsonNode data) {
        public Error {
            data = data == null ? null : data.deepCopy();
        }

        @Override
        public JsonNode data() {
            return data == null ? null : data.deepCopy();
        }
    }
}
