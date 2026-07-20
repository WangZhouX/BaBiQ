package com.wzx.babiq.server.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.attachment.AttachmentContent;
import com.wzx.babiq.server.attachment.AttachmentContentLoader;
import com.wzx.babiq.server.attachment.AttachmentErrorCode;
import com.wzx.babiq.server.attachment.AttachmentException;
import com.wzx.babiq.server.attachment.AttachmentMetadata;
import com.wzx.babiq.server.attachment.AttachmentSource;
import com.wzx.babiq.server.attachment.AttachmentTextSegment;
import com.wzx.babiq.server.attachment.PreparedAttachment;
import com.wzx.babiq.server.attachment.PreparedTurnInput;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntime;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeInput;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeResult;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.ThreadItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 附件进入 AgentLoop 后的瞬时内容装配测试。
 */
class AgentLoopAttachmentTest {

    @Test
    void document_only_turn_loads_new_and_referenced_text_but_keeps_string_stream() throws Exception {
        Fixture fixture = fixture("turn_docs", "thr_docs");
        PreparedAttachment newDocument = attachment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c289", "A-7K3M2Q",
                "new.txt", "text/plain", "1".repeat(64));
        PreparedAttachment referencedDocument = attachment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c290", "A-8N4P3R",
                "history.pdf", "application/pdf", "2".repeat(64));
        AttachmentTextSegment newSegment = segment(newDocument, "new document body");
        AttachmentTextSegment referencedSegment = segment(referencedDocument, "referenced document body");
        PreparedTurnInput input = new PreparedTurnInput(
                "请比较两份资料", List.of(newDocument), List.of(referencedDocument));

        when(fixture.loader.load(input.allAttachments())).thenReturn(List.of(
                AttachmentContent.document(newDocument, newSegment),
                AttachmentContent.document(referencedDocument, referencedSegment)));
        when(fixture.runtime.prepare(any())).thenReturn(
                ContextWindowRuntimeResult.prepared("ctx_docs", input.text(), "MODEL_DOCUMENT_CONTEXT"));
        fixture.stubSuccessfulStringStream("MODEL_DOCUMENT_CONTEXT");

        fixture.loop.invoke(
                fixture.turn, input, "provider-a", ".", fixture.emitter, null, null);

        ArgumentCaptor<ContextWindowRuntimeInput> runtimeInput =
                ArgumentCaptor.forClass(ContextWindowRuntimeInput.class);
        verify(fixture.runtime).prepare(runtimeInput.capture());
        assertThat(runtimeInput.getValue().attachmentTextSegments())
                .containsExactly(newSegment, referencedSegment);
        verify(fixture.agent).stream(eq("MODEL_DOCUMENT_CONTEXT"), eq(fixture.config));
        verify(fixture.agent, never()).stream(any(UserMessage.class), any(RunnableConfig.class));
        assertThat(fixture.userMessage().attachments())
                .containsExactly(newDocument.metadata());
    }

    @Test
    void image_turn_streams_user_message_with_exact_mime_and_bytes_without_local_metadata() throws Exception {
        Fixture fixture = fixture("turn_image", "thr_image");
        byte[] exactBytes = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        PreparedAttachment image = attachment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c291", "A-9Q5R4T",
                "screen.png", "image/png", "a".repeat(64));
        PreparedTurnInput input = new PreparedTurnInput(
                "解释图片", List.of(image), List.of());
        when(fixture.loader.load(input.allAttachments()))
                .thenReturn(List.of(AttachmentContent.image(image, exactBytes)));
        when(fixture.runtime.prepare(any())).thenReturn(
                ContextWindowRuntimeResult.prepared(
                        "ctx_image", input.text(), "MODEL_IMAGE_CONTEXT"));
        when(fixture.agent.stream(any(UserMessage.class), eq(fixture.config)))
                .thenReturn(Flux.just(fixture.output));
        when(fixture.strategy.extractAssistantMessage(fixture.output))
                .thenReturn(new AssistantMessage("done"));

        fixture.loop.invoke(
                fixture.turn, input, "provider-a", ".", fixture.emitter, null, null);

        ArgumentCaptor<UserMessage> message = ArgumentCaptor.forClass(UserMessage.class);
        verify(fixture.agent).stream(message.capture(), eq(fixture.config));
        verify(fixture.agent, never()).stream(any(String.class), any(RunnableConfig.class));
        assertThat(message.getValue().getText())
                .isEqualTo("MODEL_IMAGE_CONTEXT")
                .doesNotContain(image.metadata().localPath(), image.metadata().sha256());
        assertThat(message.getValue().getMedia()).singleElement().satisfies(media -> {
            assertThat(media.getMimeType().toString()).isEqualTo("image/png");
            assertThat(media.getDataAsByteArray()).containsExactly(exactBytes);
            assertThat(media.getId()).isNull();
            assertThat(media.getName()).isEmpty();
        });
    }

    @Test
    void changed_attachment_fails_before_context_and_model_invocation() throws Exception {
        Fixture fixture = fixture("turn_changed", "thr_changed");
        PreparedAttachment image = attachment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c292", "A-AB3C4D",
                "private.png", "image/png", "b".repeat(64));
        PreparedTurnInput input = new PreparedTurnInput("", List.of(image), List.of());
        String forbiddenPath = image.metadata().localPath();
        when(fixture.loader.load(input.allAttachments())).thenThrow(new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_CHANGED,
                "附件自发送后已变化，请重新选择"));

        fixture.loop.invoke(
                fixture.turn, input, "provider-a", ".", fixture.emitter, null, null);

        assertThat(fixture.turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(fixture.turn.failureReason())
                .contains("ATTACHMENT_CHANGED")
                .doesNotContain(forbiddenPath);
        assertThat(fixture.userMessage().attachments()).containsExactly(image.metadata());
        verifyNoInteractions(fixture.runtime, fixture.agent);
        verify(fixture.strategy, never()).buildAgent(any(), any(), any(), any(), any());
    }

    @Test
    void missing_attachment_fails_before_context_and_both_model_stream_paths_without_logging_path()
            throws Exception {
        Fixture fixture = fixture("turn_missing", "thr_missing");
        PreparedAttachment image = attachment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c294", "A-JK7L8M",
                "missing-private.png", "image/png", "d".repeat(64));
        PreparedTurnInput input = new PreparedTurnInput("", List.of(image), List.of());
        String forbiddenPath = image.metadata().localPath();
        when(fixture.loader.load(input.allAttachments())).thenThrow(new AttachmentException(
                AttachmentErrorCode.ATTACHMENT_NOT_FOUND,
                "附件已不存在，请重新选择"));

        Logger logger = (Logger) LoggerFactory.getLogger(AgentLoop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            fixture.loop.invoke(
                    fixture.turn, input, "provider-a", ".", fixture.emitter, null, null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(fixture.turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(fixture.turn.failureReason())
                .contains("ATTACHMENT_NOT_FOUND")
                .doesNotContain(forbiddenPath);
        verify(fixture.emitter).emitTurnFailed(fixture.turn.failureReason());
        verifyNoInteractions(fixture.runtime);
        verify(fixture.strategy, never()).buildAgent(any(), any(), any(), any(), any());
        verify(fixture.agent, never()).stream(any(String.class), any(RunnableConfig.class));
        verify(fixture.agent, never()).stream(any(UserMessage.class), any(RunnableConfig.class));
        String diagnosticLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(diagnosticLogs)
                .contains("reasonCode=ATTACHMENT_NOT_FOUND")
                .doesNotContain(forbiddenPath, image.metadata().name());
    }

    @Test
    void known_provider_image_rejection_is_mapped_without_echoing_remote_body() throws Exception {
        Fixture fixture = fixture("turn_rejected", "thr_rejected");
        PreparedAttachment image = attachment(
                "018fb799-2b03-7e7b-8f4c-4df90bc8c293", "A-EF5G6H",
                "screen.webp", "image/webp", "c".repeat(64));
        String privateCwd = "C:\\Users\\secret\\private-workspace";
        String privateInputPath = "D:\\business\\private\\customer-contract.xlsx";
        PreparedTurnInput input = new PreparedTurnInput(
                "请分析 " + privateInputPath, List.of(image), List.of());
        when(fixture.loader.load(input.allAttachments()))
                .thenReturn(List.of(AttachmentContent.image(image, new byte[]{1, 2, 3})));
        when(fixture.runtime.prepare(any())).thenReturn(
                ContextWindowRuntimeResult.prepared("ctx_rejected", input.text(), "MODEL_TEXT"));
        when(fixture.strategy.buildAgent(
                eq("provider-a"), eq(privateCwd), eq(fixture.emitter),
                any(TurnObservationContext.class), nullable(AgentRunPolicy.class)))
                .thenReturn(fixture.agent);
        when(fixture.strategy.buildConfig(
                eq(fixture.turn.threadId()), eq(privateCwd), eq(fixture.emitter),
                any(TurnObservationContext.class), nullable(AgentRunPolicy.class)))
                .thenReturn(fixture.config);
        WebClientResponseException rejection = WebClientResponseException.create(
                400,
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"image input is not supported; secret-remote-body\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        String forbiddenPath = image.metadata().localPath();
        RuntimeException wrappedRejection = new RuntimeException(
                "provider-body=secret-remote-body localPath=" + forbiddenPath,
                rejection);
        when(fixture.agent.stream(any(UserMessage.class), eq(fixture.config)))
                .thenReturn(Flux.error(wrappedRejection));

        Logger logger = (Logger) LoggerFactory.getLogger(AgentLoop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            fixture.loop.invoke(
                    fixture.turn, input, "provider-a", privateCwd, fixture.emitter, null, null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(fixture.turn.status()).isEqualTo(TurnStatus.FAILED);
        assertThat(fixture.turn.failureReason())
                .contains("ATTACHMENT_MODEL_UNSUPPORTED")
                .doesNotContain("secret-remote-body", "400 Bad Request", forbiddenPath);
        verify(fixture.emitter).emitTurnFailed(fixture.turn.failureReason());
        String diagnosticLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(diagnosticLogs)
                .contains("reasonCode=ATTACHMENT_MODEL_UNSUPPORTED")
                .contains("reasonType=RuntimeException")
                .doesNotContain(
                        "secret-remote-body",
                        forbiddenPath,
                        image.metadata().name(),
                        privateCwd,
                        privateInputPath);
    }

    private static AttachmentTextSegment segment(PreparedAttachment attachment, String body) {
        AttachmentMetadata metadata = attachment.metadata();
        return new AttachmentTextSegment(
                metadata.id(), metadata.displayId(), metadata.name(), metadata.mediaType(), body);
    }

    private static PreparedAttachment attachment(
            String id,
            String displayId,
            String name,
            String mediaType,
            String sha256
    ) {
        String localPath = Path.of("C:\\private", name).toAbsolutePath().normalize().toString();
        AttachmentMetadata metadata = new AttachmentMetadata(
                id, displayId, name, localPath, mediaType, 7, sha256, AttachmentSource.SELECTED_FILE);
        return new PreparedAttachment(
                metadata,
                Path.of(localPath),
                new PreparedAttachment.FileIdentity(7, FileTime.fromMillis(1), "file-key"));
    }

    private static Fixture fixture(String turnId, String threadId) {
        ReActStrategy strategy = mock(ReActStrategy.class);
        ReactAgent agent = mock(ReactAgent.class);
        NodeOutput output = mock(NodeOutput.class);
        ContextWindowRuntime runtime = mock(ContextWindowRuntime.class);
        AttachmentContentLoader loader = mock(AttachmentContentLoader.class);
        TurnSummaryEmitter summaryEmitter = mock(TurnSummaryEmitter.class);
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Turn turn = new Turn(turnId, threadId);
        turn.start();
        List<ThreadItem> emitted = new ArrayList<>();
        ItemEmitter emitter = capturingEmitter(emitted);
        AgentLoop loop = new AgentLoop(
                strategy,
                new PendingApprovals(),
                summaryEmitter,
                new TurnObservationRegistry(),
                runtime,
                loader);
        when(strategy.resolveModelName("provider-a")).thenReturn("mock-react");
        when(strategy.resolveContextWindow("provider-a")).thenReturn(128_000);
        when(strategy.currentToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        when(strategy.buildAgent(
                eq("provider-a"), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class))).thenReturn(agent);
        when(strategy.buildConfig(
                eq(threadId), eq("."), eq(emitter), any(TurnObservationContext.class),
                nullable(AgentRunPolicy.class))).thenReturn(config);
        return new Fixture(
                strategy, agent, output, runtime, loader, loop, turn, emitter, config, emitted);
    }

    private static ItemEmitter capturingEmitter(List<ThreadItem> emitted) {
        ItemEmitter emitter = mock(ItemEmitter.class);
        try {
            doAnswer(invocation -> {
                emitted.add(invocation.getArgument(0));
                return null;
            }).when(emitter).emitItemAdded(any(ThreadItem.class));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return emitter;
    }

    private record Fixture(
            ReActStrategy strategy,
            ReactAgent agent,
            NodeOutput output,
            ContextWindowRuntime runtime,
            AttachmentContentLoader loader,
            AgentLoop loop,
            Turn turn,
            ItemEmitter emitter,
            RunnableConfig config,
            List<ThreadItem> emitted
    ) {
        private void stubSuccessfulStringStream(String text) throws Exception {
            when(agent.stream(eq(text), eq(config))).thenReturn(Flux.just(output));
            when(strategy.extractAssistantMessage(output)).thenReturn(new AssistantMessage("done"));
        }

        private UserMessageItem userMessage() {
            return emitted.stream()
                    .filter(UserMessageItem.class::isInstance)
                    .map(UserMessageItem.class::cast)
                    .findFirst()
                    .orElseThrow();
        }
    }
}
