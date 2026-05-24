package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.observability.RunRecordService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * run/turn/get 方法处理器。
 *
 * <p>该接口返回单个 turn 的详细轨迹，包含 item、summary、审批和工具调用。
 * UI 可以先用 run/turns/list 画列表，再按需调用本接口展开详情。</p>
 */
@Component
public class RunTurnGetHandler implements JsonRpcMethodHandler {

    /** 运行记录聚合服务。 */
    private final RunRecordService runRecordService;

    /**
     * 创建 run/turn/get handler。
     *
     * @param runRecordService 运行记录聚合服务
     */
    public RunTurnGetHandler(RunRecordService runRecordService) {
        this.runRecordService = runRecordService;
    }

    @Override
    public String method() {
        return "run/turn/get";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String turnId = requiredText(params, "turnId");
        try {
            return runRecordService.getTurn(turnId);
        } catch (IllegalArgumentException exception) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, exception.getMessage());
        }
    }

    private static String requiredText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return params.get(fieldName).asText();
    }
}
