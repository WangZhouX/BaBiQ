package com.wzx.babiq.server.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 输出单行 JSON 的 turn 摘要日志。
 */
@Component
public class StructuredTurnLogger {

    private static final Logger log = LoggerFactory.getLogger(StructuredTurnLogger.class);
    /** turn 结构化日志的 JSON 序列化器，失败时会退回普通日志避免影响主流程。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void logSummary(TurnObservationContext context, TurnSummaryItem item) {
        try {
            log.info(toJson(context, item));
        } catch (JsonProcessingException exception) {
            log.warn("序列化 turn 摘要日志失败,turnId={}", context.turnId(), exception);
        }
    }

    public String toJson(TurnObservationContext context, TurnSummaryItem item) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "agent.turn.summary");
        payload.put("threadId", context.threadId());
        payload.put("turnId", context.turnId());
        payload.put("providerId", context.providerId());
        payload.put("model", item.model());
        payload.put("status", item.status());
        payload.put("promptTokens", item.promptTokens());
        payload.put("completionTokens", item.completionTokens());
        payload.put("totalTokens", item.totalTokens());
        payload.put("toolCalls", item.toolCalls());
        payload.put("estimatedCostUsd", item.estimatedCostUsd());
        payload.put("durationMs", item.durationMs());
        return objectMapper.writeValueAsString(payload);
    }
}
