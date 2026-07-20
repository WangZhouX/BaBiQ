package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.attachment.AttachmentContent;
import com.wzx.babiq.server.attachment.AttachmentContentLoader;
import com.wzx.babiq.server.attachment.AttachmentException;
import com.wzx.babiq.server.attachment.AttachmentModelFailureClassifier;
import com.wzx.babiq.server.attachment.AttachmentTextSegment;
import com.wzx.babiq.server.attachment.PreparedTurnInput;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.slf4j.Logger;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * AgentLoop 的支撑工具，集中承载失败收口与瞬时附件模型输入。
 */
final class AgentLoopSupport {

    static final String GENERIC_FAILURE_REASON =
            "AGENT_EXECUTION_FAILED: Agent 执行失败，请稍后重试";
    private static final AttachmentModelFailureClassifier MODEL_FAILURE_CLASSIFIER =
            new AttachmentModelFailureClassifier();

    private AgentLoopSupport() {
    }

    /**
     * 生成不含业务信息的 item id。
     */
    static String newItemId() {
        return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 兼容无图片的旧调用点。
     */
    static void fail(Logger logger,
                     Turn turn,
                     ItemEmitter emitter,
                     Exception exception,
                     TurnSummaryEmitter summaryEmitter,
                     TurnObservationContext context,
                     TurnObservationRegistry observationRegistry) {
        fail(logger, turn, emitter, exception, summaryEmitter, context, observationRegistry, false);
    }

    /**
     * 处理失败或中断，并严格分离内部类型诊断与用户可见安全原因。
     */
    static void fail(Logger logger,
                     Turn turn,
                     ItemEmitter emitter,
                     Exception exception,
                     TurnSummaryEmitter summaryEmitter,
                     TurnObservationContext context,
                     TurnObservationRegistry observationRegistry,
                     boolean imageInputPresent) {
        if (isInterrupted(exception) || Thread.currentThread().isInterrupted()) {
            logger.warn("AgentLoop 被中断 turnId={},reasonType={}",
                    turn.id(), exception.getClass().getSimpleName());
            try {
                if (!turn.status().isTerminal()) {
                    turn.cancel();
                }
                emitSummary(logger, turn, emitter, summaryEmitter, context, "interrupted");
                observationRegistry.remove(turn.id());
                emitter.emitTurnCompleted("interrupted");
            } catch (Exception sendException) {
                logger.error("发送 turn/completed(interrupted) 失败 turnId={},reasonType={}",
                        turn.id(), sendException.getClass().getSimpleName());
            }
            return;
        }
        String failureMessage = safeFailureReason(exception, imageInputPresent);
        logger.error("AgentLoop 执行失败 turnId={},reasonType={},safeReason={}",
                turn.id(), exception.getClass().getSimpleName(), failureMessage);
        if (!turn.status().isTerminal()) {
            turn.fail(failureMessage);
        }
        try {
            emitSummary(logger, turn, emitter, summaryEmitter, context, "failed");
            observationRegistry.remove(turn.id());
            emitter.emitTurnFailed(failureMessage);
        } catch (Exception sendException) {
            logger.error("发送 turn/failed 失败 turnId={},reasonType={}",
                    turn.id(), sendException.getClass().getSimpleName());
        }
    }

    private static void emitSummary(Logger logger,
                                    Turn turn,
                                    ItemEmitter emitter,
                                    TurnSummaryEmitter summaryEmitter,
                                    TurnObservationContext context,
                                    String status) {
        try {
            summaryEmitter.emit(context, emitter, status);
        } catch (Exception exception) {
            logger.warn("发送 turnSummary 失败 turnId={},status={},reasonType={}",
                    turn.id(), status, exception.getClass().getSimpleName());
        }
    }

    /**
     * 生成可持久化、可发往 UI 的稳定失败原因，不回显异常原文或远端响应正文。
     */
    static String safeFailureReason(Exception exception, boolean imageInputPresent) {
        AttachmentException attachmentFailure = findCause(exception, AttachmentException.class);
        if (attachmentFailure == null) {
            attachmentFailure = MODEL_FAILURE_CLASSIFIER
                    .classify(exception, imageInputPresent)
                    .orElse(null);
        }
        if (attachmentFailure == null) {
            return GENERIC_FAILURE_REASON;
        }
        return attachmentFailure.code().name() + ": " + attachmentFailure.safeMessage();
    }

    /**
     * 沿 cause 链寻找指定异常类型，限制深度以抵御异常构造错误。
     */
    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * 判断异常是否代表中断。
     */
    static boolean isInterrupted(Exception exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 同步装载并复核本轮全部附件；返回对象只在一次模型调用期间持有正文和图片字节。
     */
    static AttachmentInvocation loadAttachments(
            AttachmentContentLoader loader,
            PreparedTurnInput input
    ) {
        if (input.allAttachments().isEmpty()) {
            return AttachmentInvocation.empty();
        }
        if (loader == null) {
            throw new IllegalStateException("attachment content loader is unavailable");
        }
        return AttachmentInvocation.from(loader.load(input.allAttachments()));
    }

    /**
     * 选择兼容的字符串流或图片 UserMessage 流并完整消费。
     */
    static AgentStreamConsumer.StreamResult stream(
            ReactAgent agent,
            String modelInputText,
            RunnableConfig config,
            ItemEmitter emitter,
            AttachmentInvocation attachments
    ) throws Exception {
        if (!attachments.hasImages()) {
            return AgentStreamConsumer.consume(agent.stream(modelInputText, config), emitter);
        }
        UserMessage message = UserMessage.builder()
                .text(modelInputText)
                .media(attachments.media())
                .build();
        return AgentStreamConsumer.consume(agent.stream(message, config), emitter);
    }

    /**
     * 一次模型调用的瞬时附件内容；关闭后主动释放装载器返回的图片数组引用。
     */
    static final class AttachmentInvocation implements AutoCloseable {
        private final List<AttachmentTextSegment> textSegments;
        private final List<AttachmentContent> images;

        private AttachmentInvocation(
                List<AttachmentTextSegment> textSegments,
                List<AttachmentContent> images
        ) {
            this.textSegments = List.copyOf(textSegments);
            this.images = new ArrayList<>(images);
        }

        static AttachmentInvocation empty() {
            return new AttachmentInvocation(List.of(), List.of());
        }

        static AttachmentInvocation from(List<AttachmentContent> contents) {
            List<AttachmentTextSegment> textSegments = new ArrayList<>();
            List<AttachmentContent> images = new ArrayList<>();
            for (AttachmentContent content : contents) {
                if (content.isImage()) {
                    images.add(content);
                } else {
                    textSegments.add(content.textSegment());
                }
            }
            return new AttachmentInvocation(textSegments, images);
        }

        List<AttachmentTextSegment> textSegments() {
            return textSegments;
        }

        boolean hasImages() {
            return !images.isEmpty();
        }

        private List<Media> media() {
            return images.stream()
                    .map(content -> Media.builder()
                            .mimeType(MimeTypeUtils.parseMimeType(
                                    content.attachment().metadata().mediaType()))
                            .data(content.imageBytes())
                            .name("")
                            .build())
                    .toList();
        }

        @Override
        public void close() {
            images.clear();
        }
    }
}
