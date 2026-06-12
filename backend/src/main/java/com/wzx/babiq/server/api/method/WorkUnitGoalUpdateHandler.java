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
 * workunit/goal/update JSON-RPC handler。
 *
 * <p>这是桌面端右侧详情面板的直接保存入口。它只修改待执行目标文本，
 * 目标状态、运行归属和生命周期仍由 {@link WorkUnitService} 统一判断。</p>
 */
@Component
public class WorkUnitGoalUpdateHandler implements JsonRpcMethodHandler {

    /** 工作容器应用服务。 */
    private final WorkUnitService service;

    public WorkUnitGoalUpdateHandler(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "workunit/goal/update";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = ContextStatusHandler.requiredText(params, "threadId");
        String workUnitId = ContextStatusHandler.requiredText(params, "workUnitId");
        String goalId = ContextStatusHandler.requiredText(params, "goalId");
        String goalText = ContextStatusHandler.requiredText(params, "goalText").trim();
        if (goalText.isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "goalText 不能为空");
        }

        WorkUnit workUnit = service.listVisible(threadId).stream()
                .filter(candidate -> workUnitId.equals(candidate.workUnitId()))
                .findFirst()
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "工作容器不存在或已移除"));
        boolean goalBelongsToWorkUnit = service.listGoals(workUnitId).stream()
                .anyMatch(goal -> goalId.equals(goal.goalId())
                        && workUnitId.equals(goal.workUnitId())
                        && threadId.equals(goal.threadId()));
        if (!goalBelongsToWorkUnit) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "目标不属于当前工作容器");
        }

        WorkUnitGoal updatedGoal = service.updateGoal(goalId, goalText);
        List<WorkUnitGoal> refreshedGoals = service.listGoals(workUnitId);
        return new WorkUnitGoalUpdateResult(
                WorkUnitListHandler.toGoalInfo(updatedGoal),
                WorkUnitListHandler.toInfo(
                        workUnit,
                        refreshedGoals,
                        WorkUnitListHandler.configFor(service, workUnitId))
        );
    }
}
