package com.wzx.babiq.server.agent;

import com.wzx.babiq.server.api.JsonRpcLogSupport;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Optional;

/**
 * AgentLoop 诊断日志。
 *
 * <p>AgentLoop 本身要保持薄编排,所以日志字段组装集中在这里。logger 仍使用
 * AgentLoop.class,方便从控制台按 AgentLoop 过滤完整执行链。</p>
 */
final class AgentLoopDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private AgentLoopDiagnostics() {
    }

    static void started(Turn turn, TurnObservationContext context, String cwd, String userText) {
        log.info("AgentLoop 开始执行: threadId={}, turnId={}, providerId={}, model={}, cwd={}, inputChars={}, inputPreview={}",
                turn.threadId(), turn.id(), context.providerId(), context.model(), cwd,
                userText == null ? 0 : userText.length(), JsonRpcLogSupport.preview(userText));
    }

    static void userItemEmitted(Turn turn) {
        log.info("AgentLoop 已发送用户消息 item: threadId={}, turnId={}", turn.threadId(), turn.id());
    }

    static void modelCallStarted(Turn turn, TurnObservationContext context) {
        log.info("AgentLoop 开始调用模型: threadId={}, turnId={}, providerId={}, model={}",
                turn.threadId(), turn.id(), context.providerId(), context.model());
    }

    static void modelCallReturned(Turn turn, Optional<?> output, long startedNanos) {
        log.info("AgentLoop 模型调用返回: threadId={}, turnId={}, outputPresent={}, elapsedMs={}",
                turn.threadId(), turn.id(), output.isPresent(), JsonRpcLogSupport.elapsedMillis(startedNanos));
    }

    static void failureClosing(Turn turn, TurnObservationContext context, Exception exception) {
        log.warn("AgentLoop 进入失败收尾: threadId={}, turnId={}, providerId={}, model={}, reason={}",
                turn.threadId(), turn.id(), context.providerId(), context.model(),
                JsonRpcLogSupport.preview(exception.getMessage()));
    }

    static void waitingApproval(Turn turn, Object metadata) {
        log.info("AgentLoop 等待人工审批: threadId={}, turnId={}, nodeType={}",
                turn.threadId(), turn.id(), metadata.getClass().getSimpleName());
    }

    static void assistantMessageExtracted(Turn turn, AssistantMessage assistantMessage) {
        log.info("AgentLoop 已提取助手消息: threadId={}, turnId={}, outputChars={}",
                turn.threadId(), turn.id(), assistantMessage.getText() == null ? 0 : assistantMessage.getText().length());
    }

    static void completed(Turn turn, TurnObservationContext context) {
        log.info("AgentLoop 正常完成: threadId={}, turnId={}, providerId={}, model={}, durationMs={}",
                turn.threadId(), turn.id(), context.providerId(), context.model(), context.durationMs());
    }
}
