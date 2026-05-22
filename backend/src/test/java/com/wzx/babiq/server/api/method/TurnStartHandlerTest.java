package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.agent.TurnExecutor;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TurnStartHandler 测试。
 *
 * <p>P1-3a 起 handler 只负责创建 turn、发 turn/started、提交 TurnExecutor。</p>
 */
class TurnStartHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_should_return_turn_id_emit_started_and_submit_executor() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread("F:/wwwxxxx/BaBiQ");
        TurnExecutor executor = mock(TurnExecutor.class);
        List<String> payloads = new ArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, executor);

        Object responsePayload = handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "providerId", "dashscope-default",
                        "input", Map.of("type", "text", "text", "ping"))),
                session);

        Map<?, ?> responseMap = (Map<?, ?>) responsePayload;
        assertThat(responseMap.get("turnId")).asString().startsWith("turn_");
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains("\"method\":\"turn/started\"");
        verify(executor).submit(any(), eq("ping"), eq("dashscope-default"), eq("F:/wwwxxxx/BaBiQ"), any());
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
