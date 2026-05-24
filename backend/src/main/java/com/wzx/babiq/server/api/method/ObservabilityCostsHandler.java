package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.observability.LocalObservabilityService;
import com.wzx.babiq.server.observability.ObservabilityCostsResult;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * observability/costs 方法处理器。
 *
 * <p>方法名保留 P2-5 旧协议兼容性，但返回内容已经改为 Provider/Model token 用量聚合。
 * 目前仍然沿用本地 SQLite 聚合，不接入外部观测平台。</p>
 */
@Component
public class ObservabilityCostsHandler implements JsonRpcMethodHandler {

    /** 本地统计服务，负责模型 token 用量聚合。 */
    private final LocalObservabilityService observabilityService;

    /**
     * 创建 observability/costs handler。
     *
     * @param observabilityService 本地统计服务
     */
    public ObservabilityCostsHandler(LocalObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Override
    public String method() {
        return "observability/costs";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String range = ObservabilityRequestParams.rangeOrDefault(params);
        String cwd = ObservabilityRequestParams.optionalCwd(params);
        try {
            return new ObservabilityCostsResult(range, observabilityService.costs(range, cwd));
        } catch (IllegalArgumentException exception) {
            throw ObservabilityRequestParams.invalidParams(exception);
        }
    }
}
