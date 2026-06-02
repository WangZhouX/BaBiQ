package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.WorkUnitRemoveResult;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * workunit/remove JSON-RPC handler。
 *
 * <p>移除动作只做软删除，用于让已完成或空闲容器从桌面端消失。</p>
 */
@Component
public class WorkUnitRemoveHandler implements JsonRpcMethodHandler {

    /** 工作容器应用服务。 */
    private final WorkUnitService service;

    public WorkUnitRemoveHandler(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "workunit/remove";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String workUnitId = ContextStatusHandler.requiredText(params, "workUnitId");
        WorkUnit removed = service.remove(workUnitId);
        return new WorkUnitRemoveResult(
                removed.workUnitId(),
                removed.kind(),
                removed.name(),
                removed.status(),
                removed.removed()
        );
    }
}
