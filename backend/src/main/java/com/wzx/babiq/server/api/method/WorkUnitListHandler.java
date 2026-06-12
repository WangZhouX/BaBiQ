package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.WorkUnitGoalInfo;
import com.wzx.babiq.server.api.dto.WorkUnitInfo;
import com.wzx.babiq.server.api.dto.WorkUnitListResult;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitConfig;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * workunit/list JSON-RPC handler。
 *
 * <p>该接口只返回未被 UI 移除的工作容器；历史审计仍保留在 SQLite 中。</p>
 */
@Component
public class WorkUnitListHandler implements JsonRpcMethodHandler {

    /** 工作容器应用服务。 */
    private final WorkUnitService service;

    public WorkUnitListHandler(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "workunit/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = ContextStatusHandler.requiredText(params, "threadId");
        List<WorkUnitInfo> workUnits = service.listVisible(threadId).stream()
                .map(workUnit -> toInfo(workUnit,
                        service.listGoals(workUnit.workUnitId()),
                        configFor(service, workUnit.workUnitId())))
                .toList();
        return new WorkUnitListResult(workUnits);
    }

    static String configJsonFor(WorkUnitService service, String workUnitId) {
        return configFor(service, workUnitId).configJson();
    }

    static WorkUnitConfigPayload configFor(WorkUnitService service, String workUnitId) {
        Optional<WorkUnitConfig> config = service.findConfig(workUnitId);
        if (config == null || config.isEmpty()) {
            return new WorkUnitConfigPayload(null, null);
        }
        WorkUnitConfig value = config.get();
        return new WorkUnitConfigPayload(value.configJson(), value.structureJson());
    }

    static WorkUnitInfo toInfo(WorkUnit workUnit, List<WorkUnitGoal> goals) {
        return toInfo(workUnit, goals, new WorkUnitConfigPayload(null, null));
    }

    static WorkUnitInfo toInfo(WorkUnit workUnit, List<WorkUnitGoal> goals, String configJson) {
        return toInfo(workUnit, goals, new WorkUnitConfigPayload(configJson, null));
    }

    static WorkUnitInfo toInfo(WorkUnit workUnit, List<WorkUnitGoal> goals, WorkUnitConfigPayload config) {
        return new WorkUnitInfo(
                workUnit.workUnitId(),
                workUnit.threadId(),
                workUnit.kind(),
                workUnit.name(),
                workUnit.status(),
                workUnit.currentGoalId(),
                workUnit.cwd(),
                workUnit.sandboxMode(),
                workUnit.removed(),
                asText(workUnit.updatedAt()),
                config == null ? null : config.configJson(),
                config == null ? null : config.structureJson(),
                goals.stream().map(WorkUnitListHandler::toGoalInfo).toList()
        );
    }

    record WorkUnitConfigPayload(String configJson, String structureJson) {
    }

    static WorkUnitGoalInfo toGoalInfo(WorkUnitGoal goal) {
        return new WorkUnitGoalInfo(
                goal.goalId(),
                goal.workUnitId(),
                goal.goalText(),
                goal.status(),
                goal.runRefType(),
                goal.runRefId(),
                goal.summary(),
                goal.errorMessage(),
                asText(goal.createdAt()),
                asText(goal.startedAt()),
                asText(goal.completedAt())
        );
    }

    private static String asText(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
