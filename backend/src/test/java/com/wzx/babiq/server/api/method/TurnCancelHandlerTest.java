package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnCancelHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_should_cancel_running_turn() {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        Turn turn = conversationService.startTurn(thread.id());
        turn.start();
        TurnCancelHandler handler = new TurnCancelHandler(conversationService);

        Object responsePayload = handler.handle(objectMapper.valueToTree(Map.of("turnId", turn.id())), null);

        assertThat(turn.status()).isEqualTo(TurnStatus.CANCELED);
        Map<?, ?> responseMap = (Map<?, ?>) responsePayload;
        assertThat(responseMap.get("ok")).isEqualTo(true);
    }

    @Test
    void unknown_turn_should_throw_invalid_params() {
        TurnCancelHandler handler = new TurnCancelHandler(new ConversationService());

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn_missing")), null))
                .isInstanceOfSatisfying(JsonRpcException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }
}
