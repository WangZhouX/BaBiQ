package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.wzx.babiq.server.api.JsonRpcMethodHandler;
import com.wzx.babiq.server.api.error.JsonRpcErrorCode;
import com.wzx.babiq.server.api.error.JsonRpcException;
import com.wzx.babiq.server.observability.RunRecordService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * run/turns/list 方法处理器。
 *
 * <p>桌面端运行详情面板通过该接口读取当前 thread 的历史 turn 列表。
 * handler 只做参数裁剪，具体聚合逻辑交给 RunRecordService。</p>
 */
@Component
public class RunTurnsListHandler implements JsonRpcMethodHandler {

    /** 运行记录服务。 */
    private final RunRecordService runRecordService;

    /**
     * 创建 run/turns/list handler。
     *
     * @param runRecordService 运行记录服务
     */
    public RunTurnsListHandler(RunRecordService runRecordService) {
        this.runRecordService = runRecordService;
    }

    @Override
    public String method() {
        return "run/turns/list";
    }

    @Override
    public Object handle(JsonNode params, WebSocketSession session) {
        String threadId = requiredText(params, "threadId");
        int limit = params == null || !params.hasNonNull("limit")
                ? 20
                : Math.max(1, Math.min(params.get("limit").asInt(20), 100));
        String cursor = optionalText(params, "cursor");
        return runRecordService.listTurns(threadId, limit, cursor);
    }

    private static String requiredText(JsonNode params, String fieldName) {
        String value = optionalText(params, fieldName);
        if (value == null) {
            throw new JsonRpcException(JsonRpcErrorCode.INVALID_PARAMS, "缺少必填字段: " + fieldName);
        }
        return value;
    }

    private static String optionalText(JsonNode params, String fieldName) {
        if (params == null || !params.hasNonNull(fieldName) || params.get(fieldName).asText().isBlank()) {
            return null;
        }
        return params.get(fieldName).asText();
    }
}
