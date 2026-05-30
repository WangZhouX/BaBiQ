package com.wzx.babiq.server.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * update_plan 工具测试。
 *
 * <p>它锁住 P4 的核心语义：计划是无副作用的协议 item，首次调用新增，
 * 同一 turn 后续调用原地更新同一个 item id。</p>
 */
class UpdatePlanToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void name_returns_protocol_tool_name() {
        assertThat(new UpdatePlanTool().name()).isEqualTo("update_plan");
    }

    @Test
    void update_plan_should_emit_added_then_updated_with_same_item_id() throws Exception {
        List<String> payloads = new ArrayList<>();
        ItemEmitter emitter = new ItemEmitter(
                recordingSession(payloads),
                objectMapper,
                "thr_1",
                "turn_1");
        TurnObservationContext observation = TurnObservationContext.start("thr_1", "turn_1", "provider", "model", () -> 0L);
        ToolContext toolContext = toolContext(emitter, observation);
        UpdatePlanTool tool = new UpdatePlanTool();

        String firstResult = tool.updatePlan(
                "完成 P4",
                "先接后端协议",
                List.of(new UpdatePlanTool.PlanStepInput("扩展协议", "in_progress", "正在扩展协议")),
                toolContext);
        String secondResult = tool.updatePlan(
                "完成 P4",
                "推进到工具实现",
                List.of(
                        new UpdatePlanTool.PlanStepInput("扩展协议", "completed", null),
                        new UpdatePlanTool.PlanStepInput("实现工具", "in_progress", "正在实现工具")),
                toolContext);

        assertThat(firstResult).isEqualTo("Plan updated");
        assertThat(secondResult).isEqualTo("Plan updated");
        assertThat(payloads).hasSize(2);
        JsonNode added = objectMapper.readTree(payloads.get(0));
        JsonNode updated = objectMapper.readTree(payloads.get(1));
        String planId = added.at("/params/item/id").asText();
        assertThat(added.get("method").asText()).isEqualTo("item/added");
        assertThat(updated.get("method").asText()).isEqualTo("item/updated");
        assertThat(updated.at("/params/item/id").asText()).isEqualTo(planId);
        assertThat(updated.at("/params/item/steps/0/status").asText()).isEqualTo("completed");
        assertThat(updated.at("/params/item/steps/1/activeForm").asText()).isEqualTo("正在实现工具");
    }

    @Test
    void update_plan_without_emitter_should_return_failure_message() {
        String result = new UpdatePlanTool().updatePlan(
                null,
                null,
                List.of(new UpdatePlanTool.PlanStepInput("扩展协议", "pending", null)),
                new ToolContext(Map.of()));

        assertThat(result).contains("Plan update failed");
    }

    private ToolContext toolContext(ItemEmitter emitter, TurnObservationContext observation) {
        Map<String, Object> context = new HashMap<>();
        context.put(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        context.put(TurnObservationContext.METADATA_KEY, observation);
        return new ToolContext(context);
    }

    private WebSocketSession recordingSession(List<String> payloads) {
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName())) {
                        payloads.add(((TextMessage) args[0]).getPayload());
                        return null;
                    }
                    if ("getId".equals(method.getName())) {
                        return "test-session";
                    }
                    if ("isOpen".equals(method.getName())) {
                        return true;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Character.TYPE) {
            return '\0';
        }
        return 0;
    }
}
