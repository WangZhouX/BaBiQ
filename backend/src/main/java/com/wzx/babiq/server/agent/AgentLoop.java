package com.wzx.babiq.server.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.capability.CapabilityExposurePlan;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntime;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeInput;
import com.wzx.babiq.server.context.runtime.ContextWindowRuntimeResult;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.UserMessageItem;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.observability.TurnObservationRegistry;
import com.wzx.babiq.server.observability.TurnSummaryEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Agent Loop 主流程，负责编排 user item、P3 上下文窗口、ReactAgent 调用和 HITL 收口。 */
@Component
public class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private final ReActStrategy strategy;
    private final TurnSummaryEmitter summaryEmitter;
    private final TurnObservationRegistry observationRegistry;
    private final AgentLoopOutputHandler outputHandler;
    private final ContextWindowRuntime contextWindowRuntime;
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter,
                     TurnObservationRegistry observationRegistry) {
        this(strategy, pendingApprovals, summaryEmitter, observationRegistry, null);
    }

    /** 生产构造器额外接入 ContextWindowRuntime；测试旧入口可以继续传 null 保持兼容。 */
    @Autowired
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter,
                     TurnObservationRegistry observationRegistry, ContextWindowRuntime contextWindowRuntime) {
        this.strategy = strategy;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
        this.outputHandler = new AgentLoopOutputHandler(strategy, pendingApprovals, summaryEmitter, observationRegistry);
        this.contextWindowRuntime = contextWindowRuntime;
    }
    /** 执行普通用户输入，使用默认运行权限快照。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) { invoke(turn, userText, providerId, cwd, emitter, strategy.defaultRunPolicy()); }
    /** 执行普通用户输入：原文进入聊天历史，临时上下文窗口文本进入模型调用。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy) { invoke(turn, userText, providerId, cwd, emitter, runPolicy, null); }
    /** 执行普通用户输入，并可选把本轮工具运行绑定到工作容器目标。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy, String workUnitGoalId) {
        TurnObservationContext context = observationRegistry.start(turn.threadId(), turn.id(), providerId, strategy.resolveModelName(providerId));
        if (workUnitGoalId != null && !workUnitGoalId.isBlank()) context.rememberWorkUnitGoalId(workUnitGoalId);
        long startedNanos = System.nanoTime();
        ContextWindowRuntimeResult contextInput = null;
        CapabilityExposurePlan exposurePlan = null;
        AgentLoopDiagnostics.started(turn, context, cwd, userText);
        try {
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText));
            AgentLoopDiagnostics.userItemEmitted(turn);
            exposurePlan = strategy.planCapabilities(turn.threadId(), turn.id());
            contextInput = prepareContextInput(turn, userText, providerId, cwd, runPolicy, emitter, exposurePlan);
            ReactAgent agent = buildAgent(providerId, cwd, emitter, context, runPolicy, exposurePlan);
            AgentLoopDiagnostics.modelCallStarted(turn, context);
            AgentStreamConsumer.StreamResult result = AgentStreamConsumer.consume(agent.stream(contextInput.modelInputText(), strategy.buildConfig(turn.threadId(), cwd, emitter, context, runPolicy)), emitter);
            recordContextUsage(contextInput, context);
            AgentLoopDiagnostics.modelCallReturned(turn, result.output(), startedNanos);
            outputHandler.handleOutput(turn, emitter, result, context, cwd, agent, runPolicy);
        } catch (Exception exception) {
            recordContextUsage(contextInput, context);
            outputHandler.forgetPaused(turn.threadId());
            AgentLoopDiagnostics.failureClosing(turn, context, exception);
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry);
        }
    }
    /** 人工审批完成后，继续执行 SAA HITL 暂停点；恢复路径不重新装配上下文。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) { invokeResume(turn, feedback, cwd, emitter, strategy.defaultRunPolicy()); }
    /** 人工审批完成后，按原 turn 的权限快照恢复执行。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy) { outputHandler.invokeResume(turn, feedback, cwd, emitter, runPolicy); }
    private ReactAgent buildAgent(String providerId, String cwd, ItemEmitter emitter, TurnObservationContext context, AgentRunPolicy runPolicy, CapabilityExposurePlan exposurePlan) {
        return exposurePlan == null ? strategy.buildAgent(providerId, cwd, emitter, context, runPolicy) : strategy.buildAgent(providerId, cwd, emitter, context, runPolicy, exposurePlan);
    }
    private ContextWindowRuntimeResult prepareContextInput(Turn turn, String userText, String providerId, String cwd,
                                                           AgentRunPolicy runPolicy, ItemEmitter emitter, CapabilityExposurePlan exposurePlan) {
        if (contextWindowRuntime == null) {
            return ContextWindowRuntimeResult.prepared(null, userText, userText);
        }
        return contextWindowRuntime.prepare(new ContextWindowRuntimeInput(turn.threadId(), turn.id(), userText,
                providerId, strategy.resolveModelName(providerId), cwd, projectId(cwd), runPolicy,
                strategy.resolveContextWindow(providerId), strategy.currentToolCallbacks(exposurePlan), emitter));
    }
    private void recordContextUsage(ContextWindowRuntimeResult contextInput, TurnObservationContext context) {
        if (contextWindowRuntime != null && contextInput != null) contextWindowRuntime.recordUsage(contextInput.snapshotId(), context);
    }
    private static String projectId(String cwd) {
        return cwd == null || cwd.isBlank() ? null : java.nio.file.Path.of(cwd).getFileName().toString();
    }
}
