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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Optional;
/** Agent Loop 主流程，只负责编排 user item、ReactAgent 调用、HITL 中断和完成态收口。 */
@Component
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** 负责创建 Spring AI Alibaba ReactAgent，是模型、工具、拦截器和 Provider 装配入口。 */
    private final ReActStrategy strategy;
    /** 保存 thread 正在等待的人工审批元数据，审批通过后从这里取回恢复点。 */
    private final PendingApprovals pendingApprovals;
    /** 在 turn 完成或失败时发送 token、耗时、工具次数等运行摘要。 */
    private final TurnSummaryEmitter summaryEmitter;
    /** 保存 turn 级观测上下文，让模型调用、工具调用和最终摘要共享同一份计数。 */
    private final TurnObservationRegistry observationRegistry;
    /** 注入主流程协作者；复杂横切逻辑继续留在 Strategy、Support、Interceptor 或 Consumer 中。 */
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter, TurnObservationRegistry observationRegistry) {
        this.strategy = strategy;
        this.pendingApprovals = pendingApprovals;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
    }
    /** 执行普通用户输入：先发用户 item，再真正走流式 ReactAgent，最后处理完成或审批中断。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) {
        TurnObservationContext context = observationRegistry.start(turn.threadId(), turn.id(), providerId, strategy.resolveModelName(providerId));
        long startedNanos = System.nanoTime();
        AgentLoopDiagnostics.started(turn, context, cwd, userText);
        try {
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText));
            AgentLoopDiagnostics.userItemEmitted(turn);
            ReactAgent agent = strategy.buildAgent(providerId, cwd, emitter, context);
            AgentLoopDiagnostics.modelCallStarted(turn, context);
            AgentStreamConsumer.StreamResult result = AgentStreamConsumer.consume(
                    agent.stream(userText, strategy.buildConfig(turn.threadId(), context)), emitter);
            AgentLoopDiagnostics.modelCallReturned(turn, result.output(), startedNanos);
            handleOutput(turn, emitter, result, context, cwd);
        } catch (Exception exception) {
            AgentLoopDiagnostics.failureClosing(turn, context, exception);
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }
    /** 人工审批完成后，从 Spring AI Alibaba 的 HITL 暂停点继续流式执行同一个 turn。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) {
        TurnObservationContext context = observationRegistry.getOrStart(turn.threadId(), turn.id(), null, strategy.resolveModelName(null));
        try {
            ReactAgent agent = strategy.buildAgent(null, cwd, emitter, context);
            AgentStreamConsumer.StreamResult result = AgentStreamConsumer.consume(
                    agent.stream(Map.of(), strategy.buildResumeConfig(turn.threadId(), feedback, context)), emitter);
            handleOutput(turn, emitter, result, context, cwd);
        } catch (Exception exception) {
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }
    /** 将 ReactAgent 的最终输出分流到 HITL 等待、流式消息完成或普通消息回退三条路径。 */
    private void handleOutput(Turn turn, ItemEmitter emitter, AgentStreamConsumer.StreamResult result,
                              TurnObservationContext context, String cwd) throws Exception {
        NodeOutput node = result.output().orElse(null);
        if (node instanceof InterruptionMetadata metadata) {
            Optional<InterruptionMetadata> autoApproved = strategy.autoApprovedFeedback(turn.threadId(), metadata);
            if (autoApproved.isPresent()) {
                invokeResume(turn, autoApproved.get(), cwd, emitter);
                return;
            }
            AgentLoopDiagnostics.waitingApproval(turn, metadata);
            pendingApprovals.put(turn.threadId(), metadata);
            turn.waitApproval();
            strategy.emitApprovalRequests(turn, emitter, metadata);
            return;
        }
        if (result.hasAssistantContent()) {
            result.completeAssistant(emitter);
        } else {
            NodeOutput completedNode = Optional.ofNullable(node)
                    .orElseThrow(() -> new IllegalStateException("ReactAgent 返回空输出"));
            AssistantMessage assistantMessage = strategy.extractAssistantMessage(completedNode);
            AgentLoopDiagnostics.assistantMessageExtracted(turn, assistantMessage);
            emitter.emitItemAdded(AgentMessageItem.full(AgentLoopSupport.newItemId(), assistantMessage.getText()));
        }
        summaryEmitter.emit(context, emitter, "completed");
        observationRegistry.remove(turn.id());
        turn.complete();
        emitter.emitTurnCompleted("completed");
        AgentLoopDiagnostics.completed(turn, context);
    }
}
