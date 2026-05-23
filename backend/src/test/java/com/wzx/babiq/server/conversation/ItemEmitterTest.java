package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.ApprovalRequestPayload;
import com.wzx.babiq.server.conversation.items.TurnSummaryItem;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ItemEmitter 测试。
 *
 * <p>这个回归锁住 approval/request 的 JSON-RPC wire format，避免以后只补方法不补通知内容时
 * 把审批事件发错方法名或发丢字段。</p>
 */
class ItemEmitterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitApprovalRequest_should_send_approval_request_notification() throws Exception {
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);

        ItemEmitter emitter = new ItemEmitter(session, objectMapper, "thr_1", "turn_1");
        ApprovalRequestPayload payload = new ApprovalRequestPayload(
                "thr_1",
                "turn_1",
                "appr_1",
                "write_file",
                "{\"path\":\"a.txt\"}",
                "需要审批");

        emitter.emitApprovalRequest(payload);

        assertThat(payloads).hasSize(1);
        JsonNode root = objectMapper.readTree(payloads.get(0));
        assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(root.get("method").asText()).isEqualTo("approval/request");

        JsonNode params = root.get("params");
        assertThat(params.get("threadId").asText()).isEqualTo("thr_1");
        assertThat(params.get("turnId").asText()).isEqualTo("turn_1");
        assertThat(params.get("itemId").asText()).isEqualTo("appr_1");
        assertThat(params.get("toolName").asText()).isEqualTo("write_file");
        assertThat(params.get("arguments").asText()).isEqualTo("{\"path\":\"a.txt\"}");
        assertThat(params.get("description").asText()).isEqualTo("需要审批");
    }

    @Test
    void emitTurnSummary_should_send_item_added_notification() throws Exception {
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        ItemEmitter emitter = new ItemEmitter(session, objectMapper, "thr_1", "turn_1");

        emitter.emitTurnSummary(new TurnSummaryItem(
                "it_13", "turnSummary", "completed", "qwen-plus",
                100L, 50L, 150L, 2, new BigDecimal("0.0014"), 1200L));

        JsonNode root = objectMapper.readTree(payloads.get(0));
        assertThat(root.get("method").asText()).isEqualTo("item/added");
        assertThat(root.at("/params/item/type").asText()).isEqualTo("turnSummary");
        assertThat(root.at("/params/item/model").asText()).isEqualTo("qwen-plus");
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
