package com.wzx.babiq.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * /ws/agent 端到端协议集成测试。
 *
 * <p>该测试启动真实 Spring Boot WebSocket 端点,使用 JDK 标准 WebSocket 客户端
 * 验证 JSON-RPC request/response 和服务端 notification 流。它是 P1-1 自动化
 * 验收的主入口。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JsonRpcWebSocketHandlerIT {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void websocket_should_complete_thread_create_and_mock_turn_flow() throws Exception {
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();
        WebSocketSession session = connect(receivedPayloads);

        session.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"thread/create\",\"params\":{\"cwd\":\".\"}}"));

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(receivedPayloads).hasSizeGreaterThanOrEqualTo(1));
        JsonNode threadResponse = objectMapper.readTree(receivedPayloads.get(0));
        String threadId = threadResponse.path("result").path("threadId").asText();
        assertThat(threadId).startsWith("thr_");

        session.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"turn/start\",\"params\":{"
                        + "\"threadId\":\"" + threadId + "\","
                        + "\"input\":{\"type\":\"text\",\"text\":\"ping\"}}}"));

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(receivedPayloads).hasSizeGreaterThanOrEqualTo(6));
        session.close();

        String allPayloads = String.join("\n", receivedPayloads);
        assertThat(allPayloads)
                .contains("\"id\":2")
                .contains("\"turnId\":\"turn_")
                .contains("\"method\":\"turn/started\"")
                .contains("\"type\":\"userMessage\"")
                .contains("\"text\":\"ping\"")
                .contains("\"type\":\"agentMessage\"")
                .contains("hello from babiq")
                .contains("\"method\":\"turn/completed\"")
                .contains("\"status\":\"completed\"");
    }

    @Test
    void websocket_should_return_method_not_found_error_code() throws Exception {
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();
        WebSocketSession session = connect(receivedPayloads);

        session.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"no/such\",\"params\":{}}"));

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(receivedPayloads).hasSize(1));
        session.close();

        assertThat(receivedPayloads.get(0))
                .contains("\"id\":7")
                .contains("\"code\":-32601")
                .contains("Method not found: no/such");
    }

    @Test
    void websocket_should_return_invalid_params_error_code() throws Exception {
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();
        WebSocketSession session = connect(receivedPayloads);

        session.sendMessage(new TextMessage(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"thread/create\",\"params\":{}}"));

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(receivedPayloads).hasSize(1));
        session.close();

        assertThat(receivedPayloads.get(0))
                .contains("\"id\":8")
                .contains("\"code\":-32602")
                .contains("缺少必填字段: cwd");
    }

    private WebSocketSession connect(List<String> receivedPayloads) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String uri = "ws://localhost:" + port + "/ws/agent";
        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                receivedPayloads.add(message.getPayload());
            }
        }, uri).get();
    }
}
