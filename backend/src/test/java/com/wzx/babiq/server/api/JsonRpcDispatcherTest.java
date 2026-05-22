package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRpcDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Logger dispatcherLogger = (Logger) LoggerFactory.getLogger(JsonRpcDispatcher.class);
    private Level previousDispatcherLevel;

    @BeforeEach
    void remember_dispatcher_log_level() {
        previousDispatcherLevel = dispatcherLogger.getLevel();
    }

    @AfterEach
    void restore_dispatcher_log_level() {
        dispatcherLogger.setLevel(previousDispatcherLevel);
    }

    @Test
    void method_not_found_should_return_minus32601() {
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(), objectMapper);
        JsonRpcMessage.Request request = new JsonRpcMessage.Request("2.0", 1L, "no/such", Map.of());

        JsonRpcMessage message = dispatcher.dispatch(request, null);

        JsonRpcMessage.ErrorResponse errorResponse = (JsonRpcMessage.ErrorResponse) message;
        assertThat(errorResponse.error().code()).isEqualTo(JsonRpcErrorCode.METHOD_NOT_FOUND.code());
        assertThat(errorResponse.id()).isEqualTo(1L);
    }

    @Test
    void jsonrpc_exception_should_keep_handler_error_code() {
        JsonRpcMethodHandler handler = new JsonRpcMethodHandler() {
            @Override
            public String method() {
                return "x/y";
            }

            @Override
            public Object handle(JsonNode params, WebSocketSession session) {
                throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少字段");
            }
        };
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(handler), objectMapper);
        JsonRpcMessage.Request request = new JsonRpcMessage.Request("2.0", 2L, "x/y", Map.of());

        JsonRpcMessage message = dispatcher.dispatch(request, null);

        JsonRpcMessage.ErrorResponse errorResponse = (JsonRpcMessage.ErrorResponse) message;
        assertThat(errorResponse.error().code()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS.code());
        assertThat(errorResponse.error().message()).isEqualTo("缺少字段");
    }

    @Test
    void successful_handler_should_return_response_with_same_id() {
        JsonRpcMethodHandler handler = new JsonRpcMethodHandler() {
            @Override
            public String method() {
                return "ping";
            }

            @Override
            public Object handle(JsonNode params, WebSocketSession session) {
                return Map.of("pong", true);
            }
        };
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(handler), objectMapper);
        JsonRpcMessage.Request request = new JsonRpcMessage.Request("2.0", 99L, "ping", Map.of());

        JsonRpcMessage message = dispatcher.dispatch(request, null);

        JsonRpcMessage.Response response = (JsonRpcMessage.Response) message;
        assertThat(response.id()).isEqualTo(99L);
        assertThat(response.result()).isEqualTo(Map.of("pong", true));
    }

    @Test
    void unexpected_handler_exception_should_return_minus32000() {
        dispatcherLogger.setLevel(Level.OFF);
        JsonRpcMethodHandler handler = new JsonRpcMethodHandler() {
            @Override
            public String method() {
                return "explode";
            }

            @Override
            public Object handle(JsonNode params, WebSocketSession session) {
                throw new IllegalStateException("boom");
            }
        };
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(List.of(handler), objectMapper);
        JsonRpcMessage.Request request = new JsonRpcMessage.Request("2.0", 3L, "explode", Map.of());

        JsonRpcMessage message = dispatcher.dispatch(request, null);

        JsonRpcMessage.ErrorResponse errorResponse = (JsonRpcMessage.ErrorResponse) message;
        assertThat(errorResponse.error().code()).isEqualTo(JsonRpcErrorCode.SERVER_ERROR.code());
        assertThat(errorResponse.error().message()).contains("boom");
    }
}
