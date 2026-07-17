package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

/**
 * TurnInterruptHandler 测试。
 *
 * <p>验证协议层会把 turn/interrupt 路由到真实 TurnExecutor。</p>
 */
class TurnInterruptHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_returns_accepted_when_executor_accepts() {
        TurnExecutor executor = mock(TurnExecutor.class);
        when(executor.interrupt("turn_1")).thenReturn(true);
        TurnInterruptHandler handler = new TurnInterruptHandler(executor);

        Object payload = handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn_1")), null);

        assertThat(((Map<?, ?>) payload).get("accepted")).isEqualTo(true);
    }

    @Test
    void handle_rejects_unknown_turn() {
        TurnExecutor executor = mock(TurnExecutor.class);
        TurnInterruptHandler handler = new TurnInterruptHandler(executor);

        assertThatThrownBy(() -> handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn_x")), null))
                .isInstanceOfSatisfying(JsonRpcException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(JsonRpcErrorCode.INVALID_PARAMS));
    }

    @Test
    void pendingApplicationActionsAreCanceledBeforeExecutorInterrupt() {
        TurnExecutor executor = mock(TurnExecutor.class);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        when(executor.interrupt("turn-1")).thenReturn(true);
        TurnInterruptHandler handler = new TurnInterruptHandler(executor, null, pending);

        handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn-1")), null);

        var order = inOrder(pending, executor);
        order.verify(pending).cancelByTurn("turn-1");
        order.verify(executor).interrupt("turn-1");
    }

    @ParameterizedTest
    @EnumSource(value = TurnStatus.class, names = {
            "COMPLETED", "FAILED", "CANCELED", "INTERRUPTED", "EXPIRED"
    })
    void persistedTerminalTurnCannotBeInterruptedAfterMemoryMiss(TurnStatus status) {
        TurnExecutor executor = mock(TurnExecutor.class);
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        PendingApplicationActions pending = mock(PendingApplicationActions.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        when(scopes.resolve(null)).thenReturn(scope);
        when(persistence.findTurn("turn-terminal", scope)).thenReturn(java.util.Optional.of(persisted(status)));
        TurnInterruptHandler handler = new TurnInterruptHandler(executor, persistence, pending, scopes);

        assertThatThrownBy(() -> handler.handle(
                objectMapper.valueToTree(Map.of("turnId", "turn-terminal")), null))
                .isInstanceOf(JsonRpcException.class);

        verify(pending, never()).cancelByTurn("turn-terminal");
        verify(executor, never()).interrupt("turn-terminal");
        verify(persistence, never()).markCanceled(
                "turn-terminal", "INTERRUPTED", "user_interrupted", scope);
    }

    @ParameterizedTest
    @EnumSource(value = TurnStatus.class, names = {"RUNNING", "WAITING_APPROVAL"})
    void persistedInterruptibleTurnKeepsExistingBehavior(TurnStatus status) {
        TurnExecutor executor = mock(TurnExecutor.class);
        TurnPersistenceService persistence = mock(TurnPersistenceService.class);
        BusinessIdentityScopeService scopes = mock(BusinessIdentityScopeService.class);
        BusinessIdentityScope scope = BusinessIdentityScope.scoped(
                "desktop", "desktop-session", "auth", 3, "user", "tenant", "platform");
        when(scopes.resolve(null)).thenReturn(scope);
        when(persistence.findTurn("turn-active", scope)).thenReturn(java.util.Optional.of(persisted(status)));
        when(executor.interrupt("turn-active")).thenReturn(true);
        TurnInterruptHandler handler = new TurnInterruptHandler(executor, persistence, null, scopes);

        Object response = handler.handle(objectMapper.valueToTree(Map.of("turnId", "turn-active")), null);

        assertThat(((Map<?, ?>) response).get("accepted")).isEqualTo(true);
        verify(executor).interrupt("turn-active");
        verify(persistence).markCanceled("turn-active", "INTERRUPTED", "user_interrupted", scope);
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
