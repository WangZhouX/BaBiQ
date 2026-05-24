package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.observability.LocalObservabilityService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * observability/snapshot 方法处理器。
 *
 * <p>该接口面向桌面端运行详情面板，返回 totals、provider/model、工具和状态分布的一次性快照。
 * 统计数据来自 SQLite 运行记录，而不是进程内临时计数器，所以后端重启后仍然可读。</p>
 */
@Component
public class ObservabilitySnapshotHandler implements JsonRpcMethodHandler {

    /** 本地统计服务，负责真正读取 SQLite 并做聚合。 */
    private final LocalObservabilityService observabilityService;

    /**
     * 创建 observability/snapshot handler。
     *
     * @param observabilityService 本地统计服务
     */
    public ObservabilitySnapshotHandler(LocalObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Override
    public String method() {
        return "observability/snapshot";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String range = ObservabilityRequestParams.rangeOrDefault(params);
        String cwd = ObservabilityRequestParams.optionalCwd(params);
        try {
            return observabilityService.snapshot(range, cwd);
        } catch (IllegalArgumentException exception) {
            throw ObservabilityRequestParams.invalidParams(exception);
        }
    }
}
