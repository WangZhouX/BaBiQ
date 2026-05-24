package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.observability.LocalObservabilityService;
import com.wzx.babiq.server.observability.ObservabilityToolsResult;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * observability/tools 方法处理器。
 *
 * <p>该接口只返回工具维度统计，适合桌面端未来做“工具使用排行榜”或局部刷新。
 * P2-5 的运行详情面板也可以先通过 snapshot 使用同一份数据。</p>
 */
@Component
public class ObservabilityToolsHandler implements JsonRpcMethodHandler {

    /** 本地统计服务，负责工具维度聚合。 */
    private final LocalObservabilityService observabilityService;

    /**
     * 创建 observability/tools handler。
     *
     * @param observabilityService 本地统计服务
     */
    public ObservabilityToolsHandler(LocalObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Override
    public String method() {
        return "observability/tools";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String range = ObservabilityRequestParams.rangeOrDefault(params);
        String cwd = ObservabilityRequestParams.optionalCwd(params);
        try {
            return new ObservabilityToolsResult(range, observabilityService.tools(range, cwd));
        } catch (IllegalArgumentException exception) {
            throw ObservabilityRequestParams.invalidParams(exception);
        }
    }
}
