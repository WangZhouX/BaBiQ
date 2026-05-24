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
import java.util.Optional;
/** Agent Loop 主流程，只负责 user item、ReactAgent 调用、HITL 中断和完成态收口。 */
@Component
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** 负责创建和配置 Spring AI Alibaba ReactAgent，是 AgentLoop 真正调用模型和工具的入口。 */
    private final ReActStrategy strategy;
    /** 保存当前 thread 正在等待的 HITL 审批元数据，用户点击审批按钮后会从这里取回恢复点。 */
    private final PendingApprovals pendingApprovals;
    /** 在 turn 完成或失败时发出 token、耗时、工具次数、估算成本等摘要 item。 */
    private final TurnSummaryEmitter summaryEmitter;
    /** 保存每个 turn 的观测上下文，保证模型调用、工具调用和最终摘要使用同一份计数数据。 */
    private final TurnObservationRegistry observationRegistry;
    /** 注入所有协作者；主循环自身保持薄编排，横切逻辑放到 Strategy/Support/Interceptor。 */
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals,
                     TurnSummaryEmitter summaryEmitter, TurnObservationRegistry observationRegistry) {
        this.strategy = strategy;
        this.pendingApprovals = pendingApprovals;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
    }
    /** 执行普通用户输入：先发用户 item，再构建本轮专属 ReactAgent，最后处理完成或审批中断。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) {
        TurnObservationContext context = observationRegistry.start(
                turn.threadId(), turn.id(), providerId, strategy.resolveModelName(providerId));
        long startedNanos = System.nanoTime();
        AgentLoopDiagnostics.started(turn, context, cwd, userText);
        try {
            // 用户消息先进入 item 流，UI 才能立即展示“后端已接收本轮输入”。
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText));
            AgentLoopDiagnostics.userItemEmitted(turn);
            // provider、cwd、emitter、观测上下文都是本轮专属，所以 agent 每轮重新装配。
            ReactAgent agent = strategy.buildAgent(providerId, cwd, emitter, context);
            AgentLoopDiagnostics.modelCallStarted(turn, context);
            Optional<NodeOutput> output = agent.invokeAndGetOutput(userText, strategy.buildConfig(turn.threadId(), context));
            AgentLoopDiagnostics.modelCallReturned(turn, output, startedNanos);
            handleOutput(turn, emitter, output, context, cwd);
        } catch (Exception exception) {
            AgentLoopDiagnostics.failureClosing(turn, context, exception);
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }
    /** 审批完成后从 SAA HITL 暂停点续跑，并复用原 turn 的观测上下文。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) {
        TurnObservationContext context = observationRegistry.getOrStart(
                turn.threadId(), turn.id(), null, strategy.resolveModelName(null));
        try {
            ReactAgent agent = strategy.buildAgent(null, cwd, emitter, context);
            Optional<NodeOutput> output = agent.invokeAndGetOutput(java.util.Map.of(),
                    strategy.buildResumeConfig(turn.threadId(), feedback, context));
            handleOutput(turn, emitter, output, context, cwd);
        } catch (Exception exception) {
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }
    /** 把 ReactAgent 输出拆成两条路径：HITL 等待审批，或正常 assistant 消息完成。 */
    private void handleOutput(Turn turn, ItemEmitter emitter, Optional<NodeOutput> output,
                              TurnObservationContext context, String cwd) throws Exception {
        NodeOutput node = output.orElseThrow(() -> new IllegalStateException("ReactAgent 返回空输出"));
        if (node instanceof InterruptionMetadata metadata) {
            Optional<InterruptionMetadata> autoApproved = strategy.autoApprovedFeedback(turn.threadId(), metadata);
            if (autoApproved.isPresent()) {
                // 命中 always 规则时不进入等待态，直接用 approved feedback 续跑当前图。
                invokeResume(turn, autoApproved.get(), cwd, emitter);
                return;
            }
            // HITL 是可恢复的等待态，不按失败处理。
            AgentLoopDiagnostics.waitingApproval(turn, metadata);
            pendingApprovals.put(turn.threadId(), metadata);
            turn.waitApproval();
            strategy.emitApprovalRequests(turn, emitter, metadata);
            return;
        }
        AssistantMessage assistantMessage = strategy.extractAssistantMessage(node);
        AgentLoopDiagnostics.assistantMessageExtracted(turn, assistantMessage);
        emitter.emitItemAdded(AgentMessageItem.full(AgentLoopSupport.newItemId(), assistantMessage.getText()));
        // summary 必须在 observationRegistry.remove 前发出，否则 token/成本上下文会丢。
        summaryEmitter.emit(context, emitter, "completed");
        observationRegistry.remove(turn.id());
        turn.complete();
        emitter.emitTurnCompleted("completed");
        AgentLoopDiagnostics.completed(turn, context);
    }
}
