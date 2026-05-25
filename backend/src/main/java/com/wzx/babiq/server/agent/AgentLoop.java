package com.wzx.babiq.server.agent;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
/** Agent Loop 主流程，只负责编排 user item、ReactAgent 调用、HITL 中断和完成态收口。 */
@Component
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    /** 负责创建 Spring AI Alibaba ReactAgent，是模型、工具、拦截器和 Provider 装配入口。 */
    private final ReActStrategy strategy;
    /** 在 turn 完成或失败时发送 token、耗时、工具次数等运行摘要。 */
    private final TurnSummaryEmitter summaryEmitter;
    /** 保存 turn 级观测上下文，让模型调用、工具调用和最终摘要共享同一份计数。 */
    private final TurnObservationRegistry observationRegistry;
    /** 处理 ReactAgent 输出后的完成、等待审批和审批恢复分流。 */
    private final AgentLoopOutputHandler outputHandler;
    /** 注入主流程协作者；复杂横切逻辑继续留在 Strategy、Support、Interceptor 或 Consumer 中。 */
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter, TurnObservationRegistry observationRegistry) {
        this.strategy = strategy;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
        this.outputHandler = new AgentLoopOutputHandler(strategy, pendingApprovals, summaryEmitter, observationRegistry);
    }
    /** 执行普通用户输入：先发用户 item，再真正走流式 ReactAgent，最后处理完成或审批中断。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) {
        invoke(turn, userText, providerId, cwd, emitter, strategy.defaultRunPolicy());
    }

    /** 执行普通用户输入，并使用 turn/start 固定下来的权限快照。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd,
                       ItemEmitter emitter, AgentRunPolicy runPolicy) {
        TurnObservationContext context = observationRegistry.start(turn.threadId(), turn.id(), providerId, strategy.resolveModelName(providerId));
        long startedNanos = System.nanoTime();
        AgentLoopDiagnostics.started(turn, context, cwd, userText);
        try {
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText));
            AgentLoopDiagnostics.userItemEmitted(turn);
            ReactAgent agent = strategy.buildAgent(providerId, cwd, emitter, context, runPolicy);
            AgentLoopDiagnostics.modelCallStarted(turn, context);
            AgentStreamConsumer.StreamResult result = AgentStreamConsumer.consume(
                    agent.stream(userText, strategy.buildConfig(turn.threadId(), cwd, emitter, context, runPolicy)), emitter);
            AgentLoopDiagnostics.modelCallReturned(turn, result.output(), startedNanos);
            outputHandler.handleOutput(turn, emitter, result, context, cwd, agent, runPolicy);
        } catch (Exception exception) {
            outputHandler.forgetPaused(turn.threadId());
            AgentLoopDiagnostics.failureClosing(turn, context, exception);
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }
    /** 人工审批完成后，从 Spring AI Alibaba 的 HITL 暂停点继续流式执行同一个 turn。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) {
        invokeResume(turn, feedback, cwd, emitter, strategy.defaultRunPolicy());
    }

    /** 人工审批完成后，按原 turn 的权限快照恢复执行。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd,
                             ItemEmitter emitter, AgentRunPolicy runPolicy) {
        outputHandler.invokeResume(turn, feedback, cwd, emitter, runPolicy);
    }
}
