package com.wzx.babiq.server.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Authenticated business-profile coverage for the complete local-document attachment path.
 *
 * <p>The WebSocket dispatcher, identity scope, {@code TurnExecutor}, {@code AgentLoop}, document
 * extraction, context runtime and persistence are real. Only the remote model boundary is replaced
 * by a deterministic recording model.</p>
 */
@ActiveProfiles("business-desktop")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessAttachmentEndToEndIT {

    private static final String TOKEN = "C".repeat(43);
    private static final String INSTANCE_ID = "41111111-1111-4111-8111-111111111111";
    private static final String SESSION_ID = "42222222-2222-4222-8222-222222222222";
    private static final String DISPLAY_ID = "A-BCDEFG";
    private static final String ATTACHMENT_ID = "00000000-0000-4000-8000-000000000711";
    private static final String DOCUMENT_BODY = "PRIVATE_EXTRACTED_ATTACHMENT_BODY_711";
    private static final Path RUNTIME = Path.of(
            "target", "business-attachment-e2e-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");
    private static final Path WORKSPACE = RUNTIME.resolve("workspace");
    private static final Path DOCUMENT = RUNTIME.resolve("private-customer-contract.txt");

    static {
        try {
            Files.createDirectories(WORKSPACE);
            Files.writeString(TOKEN_FILE, TOKEN, StandardCharsets.US_ASCII);
            Files.writeString(DOCUMENT, DOCUMENT_BODY, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void businessRuntime(DynamicPropertyRegistry registry) {
        registry.add("babiq.business.runtime-dir", RUNTIME::toString);
        registry.add("babiq.business.session-token-file", TOKEN_FILE::toString);
        registry.add("babiq.persistence.database-path", () -> RUNTIME.resolve("data/attachments.db").toString());
        registry.add("babiq.memory.long-term.enabled", () -> "false");
        registry.add("babiq.memory.long-term.generate-enabled", () -> "false");
        registry.add("babiq.memory.long-term.read-enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private ChatClientFactory chatClientFactory;

    @Test
    void authenticatedDocumentTurnPersistsMetadataAndSupportsPathFreeStableReference() throws Exception {
        RecordingChatModel model = new RecordingChatModel();
        Mockito.when(chatClientFactory.resolveChatModel("dashscope-default")).thenReturn(model);
        Mockito.when(chatClientFactory.resolveChatModel(null)).thenReturn(model);
        Mockito.when(chatClientFactory.resolveModelName("dashscope-default")).thenReturn("capture-model");
        Mockito.when(chatClientFactory.resolveModelName(null)).thenReturn("capture-model");
        Mockito.when(chatClientFactory.resolveContextWindow("dashscope-default")).thenReturn(128_000);
        Mockito.when(chatClientFactory.resolveContextWindow(null)).thenReturn(128_000);

        List<String> inbound = new CopyOnWriteArrayList<>();
        List<String> outbound = new CopyOnWriteArrayList<>();
        Logger packageLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        packageLogger.addAppender(logs);
        try (WebSocketSession session = connect(inbound)) {
            assertThat(Files.notExists(TOKEN_FILE)).isTrue();
            request(session, inbound, outbound, 1, "application/identity/bind", identity());
            JsonNode thread = request(session, inbound, outbound, 2, "thread/create",
                    json.createObjectNode().put("cwd", WORKSPACE.toString()));
            String threadId = thread.path("threadId").asText();

            com.fasterxml.jackson.databind.node.ObjectNode firstInput =
                    json.createObjectNode().put("text", "请总结附件");
            firstInput.putArray("attachments").add(json.createObjectNode()
                    .put("id", ATTACHMENT_ID)
                    .put("displayId", DISPLAY_ID)
                    .put("name", "client-spoofed-name.txt")
                    .put("localPath", DOCUMENT.toString()));
            JsonNode firstTurn = request(session, inbound, outbound, 3, "turn/start",
                    json.createObjectNode()
                            .put("threadId", threadId)
                            .set("input", firstInput));
            String firstTurnId = firstTurn.path("turnId").asText();
            awaitTurnCompleted(inbound, firstTurnId);

            JsonNode firstUserMessage = awaitUserMessage(inbound, firstTurnId);
            assertThat(firstUserMessage.path("attachments")).hasSize(1);
            JsonNode persisted = firstUserMessage.path("attachments").get(0);
            assertThat(persisted.path("id").asText()).isEqualTo(ATTACHMENT_ID);
            assertThat(persisted.path("displayId").asText()).isEqualTo(DISPLAY_ID);
            assertThat(persisted.path("name").asText()).isEqualTo(DOCUMENT.getFileName().toString());
            assertThat(persisted.path("mediaType").asText()).startsWith("text/plain");
            assertThat(persisted.path("localPath").asText()).isEqualTo(DOCUMENT.toString());
            assertThat(persisted.path("sha256").asText()).hasSize(64);

            com.fasterxml.jackson.databind.node.ObjectNode secondInput = json.createObjectNode().put(
                    "text", "请再次读取并总结 " + DISPLAY_ID);
            secondInput.putArray("attachments");
            JsonNode secondTurn = request(session, inbound, outbound, 4, "turn/start",
                    json.createObjectNode()
                            .put("threadId", threadId)
                            .set("input", secondInput));
            String secondTurnId = secondTurn.path("turnId").asText();
            awaitTurnCompleted(inbound, secondTurnId);

            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(model.prompts()).hasSize(2));
            assertThat(model.prompts().get(0))
                    .contains(DOCUMENT_BODY, DISPLAY_ID)
                    .doesNotContain(DOCUMENT.toString());
            assertThat(model.prompts().get(1))
                    .contains(DOCUMENT_BODY, DISPLAY_ID)
                    .doesNotContain(DOCUMENT.toString());

            JsonNode status = request(session, inbound, outbound, 5, "context/status",
                    json.createObjectNode().put("threadId", threadId));
            JsonNode snapshot = request(session, inbound, outbound, 6, "context/snapshot/get",
                    json.createObjectNode().put("snapshotId", status.path("lastSnapshotId").asText()));
            assertThat(snapshot.toString())
                    .contains(DISPLAY_ID)
                    .doesNotContain(DOCUMENT.toString(), DOCUMENT_BODY);

            String wire = String.join("\n", outbound) + "\n" + String.join("\n", inbound);
            assertThat(wire)
                    .doesNotContain(DOCUMENT_BODY)
                    .doesNotContain(Base64.getEncoder().encodeToString(
                            DOCUMENT_BODY.getBytes(StandardCharsets.UTF_8)))
                    .doesNotContain("data:application", "data:text/plain");
            assertPathAppearsOnlyInAttachmentFields(outbound, inbound);
            String diagnostic = logs.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(diagnostic).doesNotContain(
                    DOCUMENT.toString(),
                    DOCUMENT.toString().replace("\\", "\\\\"),
                    DOCUMENT.toUri().toString(),
                    DOCUMENT_BODY);
            assertThat(inbound.stream().map(this::read)
                    .filter(node -> node.has("error"))
                    .toList()).isEmpty();
        } finally {
            packageLogger.detachAppender(logs);
            logs.stop();
        }
    }

    private void assertPathAppearsOnlyInAttachmentFields(List<String> outbound, List<String> inbound) {
        List<String> combined = new ArrayList<>(outbound);
        combined.addAll(inbound);
        List<String> containingPath = combined.stream()
                .filter(frame -> frame.contains(DOCUMENT.toString().replace("\\", "\\\\")))
                .toList();
        assertThat(containingPath).hasSize(2);
        assertThat(read(containingPath.get(0)).path("method").asText()).isEqualTo("turn/start");
        assertThat(read(containingPath.get(0)).path("params").path("input")
                .path("attachments").get(0).path("localPath").asText()).isEqualTo(DOCUMENT.toString());
        JsonNode notification = read(containingPath.get(1));
        assertThat(notification.path("method").asText()).isEqualTo("item/added");
        assertThat(notification.path("params").path("item").path("type").asText()).isEqualTo("userMessage");
        assertThat(notification.path("params").path("item").path("attachments")
                .get(0).path("localPath").asText()).isEqualTo(DOCUMENT.toString());
    }

    private JsonNode identity() {
        JsonNode payload = json.createObjectNode()
                .put("protocolVersion", "1.0")
                .put("desktopInstanceId", INSTANCE_ID)
                .put("desktopSessionId", SESSION_ID)
                .put("sequence", 1)
                .put("generatedAt", "2026-07-21T00:00:00Z")
                .put("authSessionId", "auth-attachment-e2e")
                .put("identityEpoch", 1)
                .put("userId", "user-attachment-e2e")
                .put("tenantId", "tenant-attachment-e2e")
                .put("platformId", "platform-attachment-e2e")
                .put("authenticated", true);
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).putArray("roles").add("lawyer");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).putArray("permissions").add("case:read");
        return payload;
    }

    private WebSocketSession connect(List<String> inbound) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        headers.set("X-Desktop-Instance-Id", INSTANCE_ID);
        headers.set("X-Desktop-Session-Id", SESSION_ID);
        headers.setOrigin("http://127.0.0.1");
        return new StandardWebSocketClient().execute(
                new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                        inbound.add(message.getPayload());
                    }
                },
                headers,
                URI.create("ws://127.0.0.1:" + port + "/ws/agent")).get();
    }

    private JsonNode request(
            WebSocketSession session,
            List<String> inbound,
            List<String> outbound,
            long id,
            String method,
            JsonNode params) throws Exception {
        String frame = json.writeValueAsString(json.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
                .set("params", params));
        outbound.add(frame);
        session.sendMessage(new TextMessage(frame));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(inbound.stream().map(this::read)
                        .anyMatch(node -> !node.has("method") && node.path("id").asLong() == id)).isTrue());
        JsonNode response = inbound.stream().map(this::read)
                .filter(node -> !node.has("method") && node.path("id").asLong() == id)
                .findFirst().orElseThrow();
        assertThat(response.path("error").isMissingNode()).as(response.toString()).isTrue();
        return response.path("result");
    }

    private void awaitTurnCompleted(List<String> inbound, String turnId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(inbound.stream()
                .map(this::read)
                .anyMatch(node -> "turn/completed".equals(node.path("method").asText())
                        && turnId.equals(node.path("params").path("turnId").asText())))
                .isTrue());
    }

    private JsonNode awaitUserMessage(List<String> inbound, String turnId) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(inbound.stream()
                .map(this::read)
                .filter(node -> "item/added".equals(node.path("method").asText()))
                .map(node -> node.path("params"))
                .anyMatch(params -> turnId.equals(params.path("turnId").asText())
                        && "userMessage".equals(params.path("item").path("type").asText())))
                .isTrue());
        return inbound.stream().map(this::read)
                .filter(node -> "item/added".equals(node.path("method").asText()))
                .map(node -> node.path("params"))
                .filter(params -> turnId.equals(params.path("turnId").asText())
                        && "userMessage".equals(params.path("item").path("type").asText()))
                .map(params -> params.path("item"))
                .findFirst().orElseThrow();
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getInstructions().stream()
                    .map(Message::getText)
                    .reduce("", (left, right) -> left + "\n" + right));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("附件已读取并完成总结。"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        private List<String> prompts() {
            return List.copyOf(prompts);
        }
    }
}
