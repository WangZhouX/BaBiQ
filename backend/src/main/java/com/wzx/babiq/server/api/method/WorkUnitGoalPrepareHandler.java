package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.WorkUnitGoalUpdateResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * workunit/goal/prepare JSON-RPC handler.
 *
 * <p>详情页进入可复用编排或团队时调用。它只准备下一轮 pending 目标，不改写 completed/failed 审计目标。</p>
 */
@Component
public class WorkUnitGoalPrepareHandler implements JsonRpcMethodHandler {

    private final WorkUnitService service;

    public WorkUnitGoalPrepareHandler(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "workunit/goal/prepare";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = ContextStatusHandler.requiredText(params, "threadId");
        String workUnitId = ContextStatusHandler.requiredText(params, "workUnitId");

        WorkUnitGoal preparedGoal = service.ensurePendingGoalForReuse(threadId, workUnitId);
        WorkUnit workUnit = service.listVisible(threadId).stream()
                .filter(candidate -> workUnitId.equals(candidate.workUnitId()))
                .findFirst()
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "WorkUnit does not exist or has been removed"));
        List<WorkUnitGoal> goals = service.listGoals(workUnitId);
        return new WorkUnitGoalUpdateResult(
                WorkUnitListHandler.toGoalInfo(preparedGoal),
                WorkUnitListHandler.toInfo(
                        workUnit,
                        goals,
                        WorkUnitListHandler.configFor(service, workUnitId))
        );
    }
}
