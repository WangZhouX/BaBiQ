package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.context.ContextStatusService;
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

    /**
     * 创建 context/snapshot/get handler。
     *
     * @param service 上下文窗口查询服务
     */
    public ContextSnapshotGetHandler(ContextStatusService service) {
        this.service = service;
    }

    @Override
    public String method() {
        return "context/snapshot/get";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String snapshotId = ContextStatusHandler.requiredText(params, "snapshotId");
        return service.snapshot(snapshotId)
                .orElseThrow(() -> new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS,
                        "上下文快照不存在: " + snapshotId));
    }
}
