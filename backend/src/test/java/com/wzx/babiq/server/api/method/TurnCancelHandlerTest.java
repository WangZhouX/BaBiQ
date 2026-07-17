package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void pendingApplicationActionsAreCanceledBeforeTurnStateChanges() {
        ConversationService conversationService = mock(ConversationService.class);
        Turn turn = mock(Turn.class);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        when(conversationService.findTurn("turn-1")).thenReturn(java.util.Optional.of(turn));
        TurnCancelHandler handler = new TurnCancelHandler(conversationService, null, pending);

        handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn-1")), null);

        var order = inOrder(pending, turn);
        order.verify(pending).cancelByTurn("turn-1");
        order.verify(turn).cancel();
    }
}
