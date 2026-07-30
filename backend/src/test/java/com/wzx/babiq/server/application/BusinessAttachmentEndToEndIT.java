package com.wzx.babiq.server.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wzx.babiq.server.model.ChatClientFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
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
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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
    private static final String MISSING_DISPLAY_ID = "A-MNPQRS";
    private static final String MISSING_ATTACHMENT_ID = "00000000-0000-4000-8000-000000000712";
    private static final String DOCUMENT_BODY = "PRIVATE_EXTRACTED_ATTACHMENT_BODY_711";
    private static final Path RUNTIME = Path.of(
            "target", "business-attachment-e2e-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final Path TOKEN_FILE = RUNTIME.resolve("session-token");
    private static final Path WORKSPACE = RUNTIME.resolve("workspace");
    private static final Path DOCUMENT = RUNTIME.resolve("private-customer-contract.txt");
    private static final Path MISSING_DOCUMENT = RUNTIME.resolve("private-missing-customer-contract.txt");

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
        registry.add("babiq.business.legacy-client-projections-enabled", () -> "true");
        registry.add("babiq.persistence.database-path", () -> RUNTIME.resolve("data/attachments.db").toString());
        registry.add("babiq.memory.long-term.enabled", () -> "false");
        registry.add("babiq.memory.long-term.generate-enabled", () -> "false");
        registry.add("babiq.memory.long-term.read-enabled", () -> "false");
    }

    @AfterAll
    static void cleanRuntimeBestEffort() {
        Path targetRoot = Path.of("target").toAbsolutePath().normalize();
        if (!RUNTIME.startsWith(targetRoot)
                || RUNTIME.getFileName() == null
                || !RUNTIME.getFileName().toString().startsWith("business-attachment-e2e-")) {
            return;
        }
        try (var paths = Files.walk(RUNTIME)) {
            List<Path> all = paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList();
            all.forEach(path -> path.toFile().deleteOnExit());
            for (int index = all.size() - 1; index >= 0; index--) {
                try {
                    Files.deleteIfExists(all.get(index));
                } catch (Exception ignored) {
                    // SQLite may stay open until the cached Spring context closes; deleteOnExit handles that boundary.
                }
            }
        } catch (Exception ignored) {
            RUNTIME.toFile().deleteOnExit();
        }
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

            ObjectNode firstInput = json.createObjectNode().put("text", "请总结附件");
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
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(model.prompts()).hasSize(1));

            JsonNode firstUserMessage = awaitUserMessage(inbound, firstTurnId);
            assertThat(firstUserMessage.path("attachments")).hasSize(1);
            JsonNode persisted = firstUserMessage.path("attachments").get(0);
            assertThat(persisted.path("id").asText()).isEqualTo(ATTACHMENT_ID);
            assertThat(persisted.path("displayId").asText()).isEqualTo(DISPLAY_ID);
            assertThat(persisted.path("name").asText()).isEqualTo(DOCUMENT.getFileName().toString());
            assertThat(persisted.path("mediaType").asText()).startsWith("text/plain");
            assertThat(persisted.path("localPath").asText()).isEqualTo(DOCUMENT.toString());
            assertThat(persisted.path("sha256").asText()).hasSize(64);

            JsonNode firstStatus = request(session, inbound, outbound, 4, "context/status",
                    json.createObjectNode().put("threadId", threadId));
            JsonNode firstSnapshot = request(session, inbound, outbound, 5, "context/snapshot/get",
                    json.createObjectNode().put("snapshotId", firstStatus.path("lastSnapshotId").asText()));
            assertSnapshotSafe(firstSnapshot);

            ObjectNode secondInput = json.createObjectNode().put(
                    "text", "请再次读取并总结 " + DISPLAY_ID);
            secondInput.putArray("attachments");
            JsonNode secondTurn = request(session, inbound, outbound, 6, "turn/start",
                    json.createObjectNode()
                            .put("threadId", threadId)
                            .set("input", secondInput));
            String secondTurnId = secondTurn.path("turnId").asText();
            awaitTurnCompleted(inbound, secondTurnId);

            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(model.prompts()).hasSize(2));
            model.prompts().forEach(prompt -> {
                assertThat(prompt.getInstructions().stream().map(Message::getText).toList())
                        .anySatisfy(text -> assertThat(text).contains(DOCUMENT_BODY, DISPLAY_ID));
                assertPromptSurfacesPathFree(prompt, DOCUMENT, MISSING_DOCUMENT);
            });

            JsonNode secondStatus = request(session, inbound, outbound, 7, "context/status",
                    json.createObjectNode().put("threadId", threadId));
            JsonNode secondSnapshot = request(session, inbound, outbound, 8, "context/snapshot/get",
                    json.createObjectNode().put("snapshotId", secondStatus.path("lastSnapshotId").asText()));
            assertSnapshotSafe(secondSnapshot);

            ObjectNode missingInput = json.createObjectNode().put("text", "请读取缺失附件");
            missingInput.putArray("attachments").add(json.createObjectNode()
                    .put("id", MISSING_ATTACHMENT_ID)
                    .put("displayId", MISSING_DISPLAY_ID)
                    .put("name", "client-missing.txt")
                    .put("localPath", MISSING_DOCUMENT.toString()));
            JsonNode missingResponse = exchange(session, inbound, outbound, 9, "turn/start",
                    json.createObjectNode()
                            .put("threadId", threadId)
                            .set("input", missingInput));
            assertThat(missingResponse.path("error").path("data").path("attachmentCode").asText())
                    .isEqualTo("ATTACHMENT_NOT_FOUND");
            assertNoPathVariants(missingResponse.path("error").toString(), MISSING_DOCUMENT);
            assertThat(model.prompts()).hasSize(2);

            String wire = String.join("\n", outbound) + "\n" + String.join("\n", inbound);
            assertThat(wire)
                    .doesNotContain(DOCUMENT_BODY)
                    .doesNotContain(Base64.getEncoder().encodeToString(
                            DOCUMENT_BODY.getBytes(StandardCharsets.UTF_8)))
                    .doesNotContain("data:application", "data:text/plain");
            assertSensitivePathPointers(outbound, inbound);
            String diagnostic = logs.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertNoPathVariants(diagnostic, DOCUMENT, MISSING_DOCUMENT);
            assertThat(diagnostic).doesNotContain(DOCUMENT_BODY);
            List<JsonNode> errorResponses = inbound.stream().map(this::read)
                    .filter(node -> node.has("error"))
                    .toList();
            assertThat(errorResponses).containsExactly(missingResponse);
        } finally {
            packageLogger.detachAppender(logs);
            logs.stop();
        }
    }

    private void assertSnapshotSafe(JsonNode snapshot) {
        String serialized = snapshot.toString();
        assertThat(serialized).contains(DISPLAY_ID).doesNotContain(DOCUMENT_BODY);
        assertNoPathVariants(serialized, DOCUMENT, MISSING_DOCUMENT);
    }

    private void assertPromptSurfacesPathFree(Prompt prompt, Path... sensitivePaths) {
        List<String> surfaces = new ArrayList<>();
        addObjectSurfaces(surfaces, prompt.getContents());
        addObjectSurfaces(surfaces, prompt);
        addObjectSurfaces(surfaces, prompt.getOptions());
        if (prompt.getOptions() != null) {
            addObjectSurfaces(surfaces, prompt.getOptions().getModel());
            addObjectSurfaces(surfaces, prompt.getOptions().getStopSequences());
        }
        for (Message message : prompt.getInstructions()) {
            addObjectSurfaces(surfaces, message.getText());
            addObjectSurfaces(surfaces, message.getMetadata());
            addObjectSurfaces(surfaces, message);
            if (message instanceof MediaContent mediaContent) {
                for (Media media : mediaContent.getMedia()) {
                    addObjectSurfaces(surfaces, media.getMimeType());
                    addObjectSurfaces(surfaces, media.getId());
                    addObjectSurfaces(surfaces, media.getName());
                    addObjectSurfaces(surfaces, media.getData());
                    if (media.getData() instanceof Resource resource) {
                        addObjectSurfaces(surfaces, resource.getDescription());
                        addObjectSurfaces(surfaces, resource.getFilename());
                        try {
                            addObjectSurfaces(surfaces, resource.getURI());
                        } catch (Exception ignored) {
                            // Some in-memory resources intentionally have no URI.
                        }
                    }
                }
            }
        }
        surfaces.forEach(surface -> assertNoPathVariants(surface, sensitivePaths));
    }

    private void addObjectSurfaces(List<String> surfaces, Object value) {
        if (value == null) {
            return;
        }
        surfaces.add(String.valueOf(value));
        try {
            surfaces.add(json.writeValueAsString(value));
        } catch (Exception ignored) {
            // toString and explicit typed getters above remain available for non-serializable options/resources.
        }
    }

    private void assertSensitivePathPointers(List<String> outbound, List<String> inbound) {
        assertThat(sensitivePointers(outbound, "outbound", DOCUMENT))
                .containsExactlyInAnyOrder(
                        new SensitivePointer("outbound#3:turn/start", "/params/input/attachments/0/localPath"));
        assertThat(sensitivePointers(inbound, "inbound", DOCUMENT))
                .containsExactlyInAnyOrder(
                        new SensitivePointer("inbound:item/added:userMessage",
                                "/params/item/attachments/0/localPath"));
        assertThat(sensitivePointers(outbound, "outbound", MISSING_DOCUMENT))
                .containsExactlyInAnyOrder(
                        new SensitivePointer("outbound#9:turn/start", "/params/input/attachments/0/localPath"));
        assertThat(sensitivePointers(inbound, "inbound", MISSING_DOCUMENT)).isEmpty();
    }

    private List<SensitivePointer> sensitivePointers(List<String> frames, String direction, Path path) {
        List<SensitivePointer> pointers = new ArrayList<>();
        for (String frame : frames) {
            JsonNode root = read(frame);
            String frameName;
            if ("outbound".equals(direction)) {
                frameName = "outbound#" + root.path("id").asText() + ":" + root.path("method").asText();
            } else {
                String itemType = root.at("/params/item/type").asText();
                frameName = "inbound:" + root.path("method").asText()
                        + (itemType.isBlank() ? "" : ":" + itemType);
            }
            collectSensitivePointers(root, "", pathVariants(path), frameName, pointers);
        }
        return pointers;
    }

    private void collectSensitivePointers(
            JsonNode node,
            String pointer,
            Set<String> variants,
            String frameName,
            List<SensitivePointer> pointers) {
        if (node.isTextual()) {
            if (variants.stream().anyMatch(node.textValue()::contains)) {
                pointers.add(new SensitivePointer(frameName, pointer));
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectSensitivePointers(
                    entry.getValue(),
                    pointer + "/" + escapePointerToken(entry.getKey()),
                    variants,
                    frameName,
                    pointers));
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectSensitivePointers(node.get(index), pointer + "/" + index, variants, frameName, pointers);
            }
        }
    }

    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private void assertNoPathVariants(String surface, Path... paths) {
        for (Path path : paths) {
            for (String variant : pathVariants(path)) {
                assertThat(surface)
                        .as("must not expose path variant %s", variant)
                        .doesNotContain(variant);
            }
        }
    }

    private static Set<String> pathVariants(Path path) {
        String raw = path.toAbsolutePath().normalize().toString();
        Set<String> variants = new LinkedHashSet<>();
        variants.add(raw);
        variants.add(raw.replace('\\', '/'));
        variants.add(path.toAbsolutePath().normalize().toUri().toString());
        variants.add(path.toAbsolutePath().normalize().toUri().toASCIIString());
        variants.add(raw.replace("\\", "\\\\"));
        variants.add(raw.replace("\\", "\\\\\\\\"));
        return Set.copyOf(variants);
    }

    private record SensitivePointer(String frame, String pointer) {
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
                URI.create("ws://127.0.0.1:" + port + "/ws/agent")).get(8, TimeUnit.SECONDS);
    }

    private JsonNode request(
            WebSocketSession session,
            List<String> inbound,
            List<String> outbound,
            long id,
            String method,
            JsonNode params) throws Exception {
        JsonNode response = exchange(session, inbound, outbound, id, method, params);
        assertThat(response.path("error").isMissingNode()).as(response.toString()).isTrue();
        return response.path("result");
    }

    private JsonNode exchange(
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
        return response;
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
        private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.copy());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("附件已读取并完成总结。"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        private List<Prompt> prompts() {
            return List.copyOf(prompts);
        }
    }
}
