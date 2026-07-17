package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(turn.status()).thenReturn(TurnStatus.RUNNING);
        TurnCancelHandler handler = new TurnCancelHandler(conversationService, null, pending);

        handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn-1")), null);

        var order = inOrder(pending, turn);
        order.verify(pending).cancelByTurn("turn-1");
        order.verify(turn).cancel();
    }

    @ParameterizedTest
    @EnumSource(value = TurnStatus.class, names = {
            "COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "EXPIRED"
    })
    void persistedTerminalTurnCannotBeCanceledAfterMemoryMiss(TurnStatus status) {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService conversationService = new ConversationService(null, persistence, null, null);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        when(scopes.resolve(null)).thenReturn(scope);
        when(persistence.findTurn("turn-terminal", scope)).thenReturn(java.util.Optional.of(persisted(status)));
        TurnCancelHandler handler = new TurnCancelHandler(conversationService, persistence, pending, scopes);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of("turnId", "turn-terminal")), null))
                .isInstanceOf(JsonRpcException.class);

        verify(pending, never()).cancelByTurn("turn-terminal");
        verify(persistence, never()).markCanceled(
                "turn-terminal", "CANCELED", "user_cancelled", scope);
    }

    @ParameterizedTest
    @EnumSource(value = TurnStatus.class, names = {"CREATED", "RUNNING", "WAITING_APPROVAL"})
    void persistedCancelableTurnRestoresItsStateBeforeCancel(TurnStatus status) {
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        ConversationService conversationService = new ConversationService(null, persistence, null, null);
        when(persistence.findTurn("turn-active")).thenReturn(java.util.Optional.of(persisted(status)));
        TurnCancelHandler handler = new TurnCancelHandler(conversationService, persistence);

        Object response = handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn-active")), null);

        assertThat(((Map<?, ?>) response).get("ok")).isEqualTo(true);
        verify(persistence).markCanceled("turn-active", "CANCELED", "user_cancelled");
    }

    private TurnEntity persisted(TurnStatus status) {
        TurnEntity entity = new TurnEntity();
        entity.setTurnId(status.isTerminal() ? "turn-terminal" : "turn-active");
        entity.setThreadId("thread-persisted");
        entity.setStatus(status.name());
        entity.setStartedAt("2026-07-17T00:00:00Z");
        return entity;
    }
}
