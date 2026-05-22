package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void request_should_roundtrip() throws Exception {
        JsonRpcMessage.Request request = new JsonRpcMessage.Request(
                "2.0", 1L, "thread/create", Map.of("cwd", "."));

        String json = objectMapper.writeValueAsString(request);
        JsonRpcMessage.Request parsedRequest = objectMapper.readValue(json, JsonRpcMessage.Request.class);

        assertThat(json)
                .contains("\"jsonrpc\":\"2.0\"")
                .contains("\"method\":\"thread/create\"")
                .contains("\"id\":1");
        assertThat(parsedRequest.id()).isEqualTo(1L);
        assertThat(parsedRequest.method()).isEqualTo("thread/create");
    }

    @Test
    void notification_should_not_carry_id() throws Exception {
        JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(
                "2.0", "turn/started", Map.of("threadId", "thr_1"));

        String json = objectMapper.writeValueAsString(notification);

        assertThat(json).doesNotContain("\"id\":");
    }

    @Test
    void error_response_should_use_jsonrpc_error_code() throws Exception {
        JsonRpcMessage.ErrorResponse errorResponse = JsonRpcMessage.ErrorResponse.of(
                42L, JsonRpcErrorCode.METHOD_NOT_FOUND, "no such method", null);

        String json = objectMapper.writeValueAsString(errorResponse);

        assertThat(json)
                .contains("\"code\":-32601")
                .contains("\"id\":42");
    }
}
