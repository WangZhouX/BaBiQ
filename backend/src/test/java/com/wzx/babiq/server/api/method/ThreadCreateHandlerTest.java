package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadCreateHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_should_create_thread_and_return_thread_id() {
        ThreadCreateHandler handler = new ThreadCreateHandler(new ConversationService());

        Object responsePayload = handler.handle(objectMapper.valueToTree(Map.of("cwd", ".")), null);

        Map<?, ?> responseMap = (Map<?, ?>) responsePayload;
        assertThat(responseMap.get("threadId")).asString().startsWith("thr_");
    }

    @Test
    void missing_cwd_should_throw_invalid_params() {
        ThreadCreateHandler handler = new ThreadCreateHandler(new ConversationService());

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of()), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
