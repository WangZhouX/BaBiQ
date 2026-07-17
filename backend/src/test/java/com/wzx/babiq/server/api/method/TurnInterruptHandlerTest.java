package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
}
