package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent Loop 主流程，负责发 user item、调用 ReactAgent、处理完成或 HITL 中断。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ReActStrategy strategy;
    private final PendingApprovals pendingApprovals;
    private final TurnSummaryEmitter summaryEmitter;
    private final TurnObservationRegistry observationRegistry;

    public AgentLoop(ReActStrategy strategy,
                     PendingApprovals pendingApprovals,
                     TurnSummaryEmitter summaryEmitter,
                     TurnObservationRegistry observationRegistry) {
        this.strategy = strategy;
        this.pendingApprovals = pendingApprovals;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
    }

    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) {
        TurnObservationContext context = observationRegistry.start(
                turn.threadId(), turn.id(), providerId, strategy.resolveModelName(providerId));
        long startedNanos = System.nanoTime();
        AgentLoopDiagnostics.started(turn, context, cwd, userText);
        try {
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText));
            AgentLoopDiagnostics.userItemEmitted(turn);
            ReactAgent agent = strategy.buildAgent(providerId, cwd, emitter, context);
            AgentLoopDiagnostics.modelCallStarted(turn, context);
            Optional<NodeOutput> output = agent.invokeAndGetOutput(userText, strategy.buildConfig(turn.threadId(), context));
            AgentLoopDiagnostics.modelCallReturned(turn, output, startedNanos);
            handleOutput(turn, emitter, output, context);
        } catch (Exception exception) {
            AgentLoopDiagnostics.failureClosing(turn, context, exception);
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }

    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) {
        TurnObservationContext context = observationRegistry.getOrStart(
                turn.threadId(), turn.id(), null, strategy.resolveModelName(null));
        try {
            ReactAgent agent = strategy.buildAgent(null, cwd, emitter, context);
            Optional<NodeOutput> output = agent.invokeAndGetOutput(java.util.Map.of(),
                    strategy.buildResumeConfig(turn.threadId(), feedback, context));
            handleOutput(turn, emitter, output, context);
        } catch (Exception exception) {
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }

    private void handleOutput(Turn turn,
                              ItemEmitter emitter,
                              Optional<NodeOutput> output,
                              TurnObservationContext context) throws Exception {
        NodeOutput node = output.orElseThrow(() -> new IllegalStateException("ReactAgent 返回空输出"));
        if (node instanceof InterruptionMetadata metadata) {
            AgentLoopDiagnostics.waitingApproval(turn, metadata);
            pendingApprovals.put(turn.threadId(), metadata);
            turn.waitApproval();
            strategy.emitApprovalRequests(turn, emitter, metadata);
            return;
        }
        AssistantMessage assistantMessage = strategy.extractAssistantMessage(node);
        AgentLoopDiagnostics.assistantMessageExtracted(turn, assistantMessage);
        emitter.emitItemAdded(AgentMessageItem.full(AgentLoopSupport.newItemId(), assistantMessage.getText()));
        summaryEmitter.emit(context, emitter, "completed");
        observationRegistry.remove(turn.id());
        turn.complete();
        emitter.emitTurnCompleted("completed");
        AgentLoopDiagnostics.completed(turn, context);
    }
}
