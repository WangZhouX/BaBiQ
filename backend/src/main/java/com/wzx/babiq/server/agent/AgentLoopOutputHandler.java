package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.TurnStatus;
import com.wzx.babiq.server.conversation.items.AgentMessageItem;
import com.wzx.babiq.server.conversation.items.ReasoningItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Optional;

/**
 * 处理 ReactAgent 输出后的状态分流。
 *
 * <p>AgentLoop 只负责编排“开始一轮”和“恢复一轮”，这里负责把 Graph 输出落到三种业务结果：
 * 等待审批、继续审批恢复、完成回答。这样主流程保持短小，HITL 细节也集中在一个地方。</p>
 */
final class AgentLoopOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopOutputHandler.class);

    /** ReAct 装配策略，负责解析最终 AssistantMessage、生成恢复配置和发送审批请求。 */
    private final ReActStrategy strategy;
    /** 内存中的待审批元数据，approval/respond 会从这里取回用户正在处理的审批。 */
    private final PendingApprovals pendingApprovals;
    /** HITL 暂停现场注册表，审批恢复必须使用同一个 ReactAgent 实例。 */
    private final PausedReactAgentRegistry pausedAgents = new PausedReactAgentRegistry();
    /** turn 完成或失败时发送运行摘要。 */
    private final TurnSummaryEmitter summaryEmitter;
    /** turn 级观测上下文注册表，用于完成后清理内存状态。 */
    private final TurnObservationRegistry observationRegistry;

    AgentLoopOutputHandler(ReActStrategy strategy, PendingApprovals pendingApprovals,
                           TurnSummaryEmitter summaryEmitter, TurnObservationRegistry observationRegistry) {
        this.strategy = strategy;
        this.pendingApprovals = pendingApprovals;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
    }

    /** 失败收口时清理暂停现场，避免下一次审批误恢复到旧图。 */
    void forgetPaused(String threadId) {
        pausedAgents.forget(threadId);
    }

    /** 处理审批响应：找回暂停中的 Agent，并从 SAA HITL 暂停点继续执行。 */
    void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd,
                      ItemEmitter emitter, AgentRunPolicy runPolicy) {
        TurnObservationContext context = observationRegistry.getOrStart(turn.threadId(), turn.id(), null, strategy.resolveModelName(null));
        ReactAgent agent = pausedAgents.take(turn.threadId());
        if (agent == null) {
            AgentLoopSupport.fail(log, turn, emitter,
                    new IllegalStateException("审批恢复失败：当前进程中不存在已暂停的 Agent 实例，请重新发起本轮任务"),
                    summaryEmitter, context, observationRegistry);
            return;
        }
        if (turn.status() == TurnStatus.WAITING_APPROVAL) {
            // 2026-05-25 Bug 修复记录：内部测试可能绕过 approval/respond 直接调用恢复入口，需要在这里补齐状态迁移。
            turn.resume();
        }
        invokeResumeWithAgent(turn, feedback, cwd, emitter, context, agent, runPolicy);
    }

    /** 将 ReactAgent 的最终输出分流到 HITL 等待、流式消息完成或普通消息回退三条路径。 */
    void handleOutput(Turn turn, ItemEmitter emitter, AgentStreamConsumer.StreamResult result,
                      TurnObservationContext context, String cwd, ReactAgent agent,
                      AgentRunPolicy runPolicy) throws Exception {
        NodeOutput node = result.output().orElse(null);
        if (node instanceof InterruptionMetadata metadata) {
            handleInterruption(turn, emitter, metadata, context, cwd, agent, runPolicy);
            return;
        }
        emitAssistantResult(turn, emitter, result, node);
        summaryEmitter.emit(context, emitter, "completed");
        observationRegistry.remove(turn.id());
        turn.complete();
        emitter.emitTurnCompleted("completed");
        AgentLoopDiagnostics.completed(turn, context);
    }

    /** 使用同一个被暂停的 ReactAgent 实例继续执行 HITL 恢复。 */
    private void invokeResumeWithAgent(Turn turn, InterruptionMetadata feedback, String cwd,
                                       ItemEmitter emitter, TurnObservationContext context,
                                       ReactAgent agent, AgentRunPolicy runPolicy) {
        try {
            AgentStreamConsumer.StreamResult result = AgentLoopResumeSupport.resumeFromApproval(
                    turn, feedback, cwd, emitter, context, agent, strategy, runPolicy);
            handleOutput(turn, emitter, result, context, cwd, agent, runPolicy);
        } catch (Exception exception) {
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }

    /** 处理 SAA HumanInTheLoopHook 返回的审批中断。 */
    private void handleInterruption(Turn turn, ItemEmitter emitter, InterruptionMetadata metadata,
                                    TurnObservationContext context, String cwd, ReactAgent agent,
                                    AgentRunPolicy runPolicy) throws Exception {
        Optional<InterruptionMetadata> autoApproved = strategy.autoApprovedFeedback(turn.threadId(), metadata);
        if (autoApproved.isPresent()) {
            invokeResumeWithAgent(turn, autoApproved.get(), cwd, emitter, context, agent, runPolicy);
            return;
        }
        AgentLoopDiagnostics.waitingApproval(turn, metadata);
        pausedAgents.remember(turn.threadId(), agent);
        pendingApprovals.put(turn.threadId(), metadata);
        turn.waitApproval();
        strategy.emitApprovalRequests(turn, emitter, metadata);
    }

    /** 输出最终助手消息；流式链路优先补全已有消息，没有流式内容时才从 NodeOutput 提取。 */
    private void emitAssistantResult(Turn turn, ItemEmitter emitter,
                                     AgentStreamConsumer.StreamResult result, NodeOutput node) throws Exception {
        if (result.hasAssistantContent()) {
            result.completeAssistant(emitter);
            return;
        }
        NodeOutput completedNode = Optional.ofNullable(node)
                .orElseThrow(() -> new IllegalStateException("ReactAgent 返回空输出"));
        AssistantMessage assistantMessage = strategy.extractAssistantMessage(completedNode);
        AgentLoopDiagnostics.assistantMessageExtracted(turn, assistantMessage);
        emitReasoningIfPresent(emitter, result, assistantMessage);
        emitter.emitItemAdded(AgentMessageItem.full(AgentLoopSupport.newItemId(), assistantMessage.getText()));
    }

    /** 把 AssistantMessage metadata 中的 reasoningContent 转成独立展示 item，且避免流式阶段重复补发。 */
    private void emitReasoningIfPresent(ItemEmitter emitter,
                                        AgentStreamConsumer.StreamResult result,
                                        AssistantMessage assistantMessage) throws Exception {
        if (result.hasReasoningContent()) {
            return;
        }
        Optional<String> reasoning = ReasoningContentSupport.extractDisplayText(assistantMessage);
        if (reasoning.isEmpty()) {
            return;
        }
        emitter.emitReasoning(new ReasoningItem(AgentLoopSupport.newItemId(), "reasoning", reasoning.get()));
    }
}
