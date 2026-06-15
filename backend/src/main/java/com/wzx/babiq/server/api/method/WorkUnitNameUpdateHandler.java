package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.WorkUnitNameUpdateResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * workunit/name/update JSON-RPC handler。
 */
@Component
public class WorkUnitNameUpdateHandler implements JsonRpcMethodHandler {

    private final WorkUnitService service;

    public WorkUnitNameUpdateHandler(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "workunit/name/update";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = ContextStatusHandler.requiredText(params, "threadId");
        String workUnitId = ContextStatusHandler.requiredText(params, "workUnitId");
        String name = ContextStatusHandler.requiredText(params, "name").trim();
        if (name.isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "name 不能为空");
        }
        service.listVisible(threadId).stream()
                .filter(candidate -> workUnitId.equals(candidate.workUnitId()))
                .findFirst()
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "工作容器不存在或已移除"));

        WorkUnit renamed = service.rename(workUnitId, name);
        return new WorkUnitNameUpdateResult(
                WorkUnitListHandler.toInfo(
                        renamed,
                        service.listGoals(workUnitId),
                        WorkUnitListHandler.configFor(service, workUnitId))
        );
    }
}
