package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.application.action.PendingApplicationActions;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * turn/interrupt 方法处理器。
 *
 * <p>P1-3a 起该 handler 连接真实 TurnExecutor，负责把用户中断请求传给正在执行的
 * Agent worker。它只处理协议参数与同步响应，实际 turn 状态收尾由 AgentLoop 负责。</p>
 */
@Component
public class TurnInterruptHandler implements JsonRpcMethodHandler {

    /** 正在运行 turn 的调度器，interrupt 请求会通过它取消后台 Future。 */
    private final TurnExecutor turnExecutor;
    /** 可选 turn 持久化服务，生产环境把主动中断写入 bq_turns。 */
    private final TurnPersistenceService turnPersistenceService;
    private PendingApplicationActions pendingApplicationActions;
    private final BusinessIdentityScopeService scopes;

    /**
     * 创建 turn/interrupt handler。
     *
     * @param turnExecutor Agent 异步执行器
     */
    public TurnInterruptHandler(TurnExecutor turnExecutor) {
        this(turnExecutor, null, null, null);
    }

    /**
     * 创建带持久化能力的 turn/interrupt handler。
     *
     * @param turnExecutor Agent 异步执行器
     * @param turnPersistenceService turn 持久化服务；为空时只取消后台任务
     */
    public TurnInterruptHandler(TurnExecutor turnExecutor, TurnPersistenceService turnPersistenceService) {
        this(turnExecutor, turnPersistenceService, null, null);
    }

    public TurnInterruptHandler(
            TurnExecutor turnExecutor,
            TurnPersistenceService turnPersistenceService,
            PendingApplicationActions pendingApplicationActions) {
        this(turnExecutor, turnPersistenceService, pendingApplicationActions, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TurnInterruptHandler(
            TurnExecutor turnExecutor,
            TurnPersistenceService turnPersistenceService,
            BusinessIdentityScopeService scopes) {
        this(turnExecutor, turnPersistenceService, null, scopes);
    }

    public TurnInterruptHandler(
            TurnExecutor turnExecutor,
            TurnPersistenceService turnPersistenceService,
            PendingApplicationActions pendingApplicationActions,
            BusinessIdentityScopeService scopes) {
        this.turnExecutor = turnExecutor;
        this.turnPersistenceService = turnPersistenceService;
        this.pendingApplicationActions = pendingApplicationActions;
        this.scopes = scopes;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPendingApplicationActions(PendingApplicationActions pendingApplicationActions) {
        this.pendingApplicationActions = pendingApplicationActions;
    }

    /**
     * 返回当前 handler 绑定的 JSON-RPC method。
     *
     * @return turn/interrupt
     */
    @Override
    public String method() {
        return "turn/interrupt";
    }

    /**
     * 中断指定 turn。
     *
     * @param params 必须包含 turnId
     * @param session 当前 WebSocket 会话，本方法不直接使用
     * @return accepted=true
     */
    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String turnId = requiredText(params, "turnId");
        BusinessIdentityScope scope = scopes == null ? null : scopes.resolve(session);
        if (turnPersistenceService != null) {
            var persisted = scope == null
                    ? turnPersistenceService.findTurn(turnId)
                    : turnPersistenceService.findTurn(turnId, scope);
            if (scope != null && persisted.isEmpty()) {
                throw unknownTurn(turnId);
            }
            if (persisted.map(entity -> TurnStatus.valueOf(entity.getStatus()).isTerminal()).orElse(false)) {
                throw unknownTurn(turnId);
            }
        }
        if (pendingApplicationActions != null) {
            pendingApplicationActions.cancelByTurn(turnId);
        }
        boolean accepted = turnExecutor.interrupt(turnId);
        if (!accepted) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "turnId 不存在或已结束: " + turnId);
        }
        if (turnPersistenceService != null) {
            if (scope == null) {
                turnPersistenceService.markCanceled(turnId, "INTERRUPTED", "user_interrupted");
            } else {
                turnPersistenceService.markCanceled(turnId, "INTERRUPTED", "user_interrupted", scope);
            }
        }
        return Map.of("accepted", true);
    }

    private static JsonRpcException unknownTurn(String turnId) {
        return new JsonRpcException(
                JsonRpcErrorCode.INVALID_PARAMS, "turnId does not exist or has ended: " + turnId);
    }

    private String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
