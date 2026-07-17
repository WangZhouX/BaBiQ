package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.context.ContextStatusService;
import com.wzx.babiq.server.application.scope.BusinessIdentityScopeService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * context/snapshot/get JSON-RPC handler。
 *
 * <p>运行详情面板和调试工具通过该接口读取某个上下文快照的 included/excluded 明细。</p>
 */
@Component
public class ContextSnapshotGetHandler implements JsonRpcMethodHandler {

    /** 上下文窗口查询服务。 */
    private final ContextStatusService service;
    private final BusinessIdentityScopeService scopes;

    /**
     * 创建 context/snapshot/get handler。
     *
     * @param service 上下文窗口查询服务
     */
    public ContextSnapshotGetHandler(ContextStatusService service) {
        this(service, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContextSnapshotGetHandler(ContextStatusService service, BusinessIdentityScopeService scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @Override
    public String method() {
        return "context/snapshot/get";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String snapshotId = ContextStatusHandler.requiredText(params, "snapshotId");
        return (scopes == null ? service.snapshot(snapshotId) : service.snapshot(snapshotId, scopes.resolve(session)))
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                        "上下文快照不存在: " + snapshotId));
    }
}
