package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.dto.WorkUnitConfigUpdateResult;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * workunit/config/update JSON-RPC handler。
 *
 * <p>该接口保存右侧 Inspector 的编排节点或团队成员配置快照。它不启动执行，
 * 也不修改目标状态；真实执行仍必须通过 start_work_unit 显式触发。</p>
 */
@Component
public class WorkUnitConfigUpdateHandler implements JsonRpcMethodHandler {

    /** 工作容器应用服务。 */
    private final WorkUnitService service;

    public WorkUnitConfigUpdateHandler(WorkUnitService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "workunit/config/update";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = ContextStatusHandler.requiredText(params, "threadId");
        String workUnitId = ContextStatusHandler.requiredText(params, "workUnitId");
        String configJson = ContextStatusHandler.requiredText(params, "configJson").trim();
        if (configJson.isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "configJson 不能为空");
        }

        WorkUnit workUnit = service.listVisible(threadId).stream()
                .filter(candidate -> workUnitId.equals(candidate.workUnitId()))
                .findFirst()
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "工作容器不存在或已移除"));

        service.updateConfig(workUnitId, configJson);
        return new WorkUnitConfigUpdateResult(
                WorkUnitListHandler.toInfo(
                        workUnit,
                        service.listGoals(workUnitId),
                        WorkUnitListHandler.configJsonFor(service, workUnitId))
        );
    }
}
