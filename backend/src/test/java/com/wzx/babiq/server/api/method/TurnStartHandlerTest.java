package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.conversation.ConversationService;
import com.wzx.babiq.server.conversation.Thread;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TurnStartHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handle_should_return_turn_id_and_emit_full_mock_stream() throws Exception {
        ConversationService conversationService = new ConversationService();
        Thread thread = conversationService.createThread(".");
        List<String> payloads = new CopyOnWriteArrayList<>();
        WebSocketSession session = recordingSession(payloads);
        TurnStartHandler handler = new TurnStartHandler(conversationService, objectMapper, "hello from babiq");

        Object responsePayload = handler.handle(
                objectMapper.valueToTree(Map.of(
                        "threadId", thread.id(),
                        "input", Map.of("type", "text", "text", "ping"))),
                session);

        Map<?, ?> responseMap = (Map<?, ?>) responsePayload;
        assertThat(responseMap.get("turnId")).asString().startsWith("turn_");
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(payloads).hasSizeGreaterThanOrEqualTo(4));

        assertThat(payloads.get(0)).contains("\"method\":\"turn/started\"");
        assertThat(payloads.get(1))
                .contains("\"method\":\"item/added\"")
                .contains("\"type\":\"userMessage\"")
                .contains("\"text\":\"ping\"");
        assertThat(payloads.get(2))
                .contains("\"method\":\"item/added\"")
                .contains("\"type\":\"agentMessage\"")
                .contains("hello from babiq");
        assertThat(payloads.get(3))
                .contains("\"method\":\"turn/completed\"")
                .contains("\"status\":\"completed\"");
    }

    private WebSocketSession recordingSession(List<String> payloads) {
        return (WebSocketSession) Proxy.newProxyInstance(
                WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class},
                (proxy, method, args) -> {
                    if ("sendMessage".equals(method.getName())) {
                        TextMessage message = (TextMessage) args[0];
                        payloads.add(message.getPayload());
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
