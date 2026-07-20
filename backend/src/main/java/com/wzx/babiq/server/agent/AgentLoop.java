package com.wzx.babiq.server.agent;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.wzx.babiq.server.attachment.PreparedTurnInput;
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
    private final com.wzx.babiq.server.attachment.AttachmentContentLoader attachmentContentLoader;
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter,
                     TurnObservationRegistry observationRegistry) { this(strategy, pendingApprovals, summaryEmitter, observationRegistry, null, null); }
    /** 兼容已接入上下文运行时但尚无附件内容加载器的旧调用点。 */
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter,
                     TurnObservationRegistry observationRegistry, ContextWindowRuntime contextWindowRuntime) { this(strategy, pendingApprovals, summaryEmitter, observationRegistry, contextWindowRuntime, null); }
    /** 生产构造器同时接入上下文运行时和有界附件内容加载器。 */
    @Autowired
    public AgentLoop(ReActStrategy strategy, PendingApprovals pendingApprovals, TurnSummaryEmitter summaryEmitter,
                     TurnObservationRegistry observationRegistry, ContextWindowRuntime contextWindowRuntime,
                     com.wzx.babiq.server.attachment.AttachmentContentLoader attachmentContentLoader) {
        this.strategy = strategy;
        this.summaryEmitter = summaryEmitter;
        this.observationRegistry = observationRegistry;
        this.outputHandler = new AgentLoopOutputHandler(strategy, pendingApprovals, summaryEmitter, observationRegistry);
        this.contextWindowRuntime = contextWindowRuntime;
        this.attachmentContentLoader = attachmentContentLoader;
    }
    /** 执行普通用户输入，使用默认运行权限快照。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter) { invoke(turn, userText, providerId, cwd, emitter, strategy.defaultRunPolicy()); }
    /** 执行普通用户输入：原文进入聊天历史，临时上下文窗口文本进入模型调用。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy) { invoke(turn, userText, providerId, cwd, emitter, runPolicy, null); }
    /** 执行普通用户输入，并可选把本轮工具运行绑定到工作容器目标。 */
    public void invoke(Turn turn, String userText, String providerId, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy, String workUnitGoalId) { invoke(turn, new PreparedTurnInput(userText, java.util.List.of(), java.util.List.of()), providerId, cwd, emitter, runPolicy, workUnitGoalId); }
    public void invoke(Turn turn, PreparedTurnInput input, String providerId, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy, String workUnitGoalId) {
        String userText = java.util.Objects.requireNonNull(input, "input").text();
        TurnObservationContext context = observationRegistry.start(turn.threadId(), turn.id(), providerId, strategy.resolveModelName(providerId), turn.businessIdentityScope());
        if (workUnitGoalId != null && !workUnitGoalId.isBlank()) context.rememberWorkUnitGoalId(workUnitGoalId);
        long startedNanos = System.nanoTime();
        ContextWindowRuntimeResult contextInput = null;
        boolean imageInputPresent = false;
        AgentLoopDiagnostics.started(turn, context, userText);
        try {
            emitter.emitItemAdded(UserMessageItem.of(AgentLoopSupport.newItemId(), userText, input.newAttachments().stream().map(item -> item.metadata()).toList()));
            AgentLoopDiagnostics.userItemEmitted(turn);
            ReactAgent agent; AgentStreamConsumer.StreamResult result;
            try (AgentLoopSupport.AttachmentInvocation attachments = AgentLoopSupport.loadAttachments(attachmentContentLoader, input)) {
                imageInputPresent = attachments.hasImages();
                CapabilityExposurePlan exposurePlan = strategy.planCapabilities(turn.threadId(), turn.id());
                contextInput = prepareContextInput(turn, userText, providerId, cwd, runPolicy, emitter, exposurePlan, attachments.textSegments());
                agent = buildAgent(providerId, cwd, emitter, context, runPolicy, exposurePlan);
                AgentLoopDiagnostics.modelCallStarted(turn, context);
                result = AgentLoopSupport.stream(agent, contextInput.modelInputText(),
                        strategy.buildConfig(turn.threadId(), cwd, emitter, context, runPolicy), emitter, attachments);
            }
            recordContextUsage(contextInput, context);
            AgentLoopDiagnostics.modelCallReturned(turn, result.output(), startedNanos);
            outputHandler.handleOutput(turn, emitter, result, context, cwd, agent, runPolicy);
        } catch (Exception exception) {
            recordContextUsage(contextInput, context);
            outputHandler.forgetPaused(turn.threadId());
            AgentLoopDiagnostics.failureClosing(turn, context, exception, AgentLoopSupport.safeFailureReason(exception, imageInputPresent));
            AgentLoopSupport.fail(log, turn, emitter, exception, summaryEmitter, context, observationRegistry, imageInputPresent);
        }
    }
    /** 人工审批完成后，继续执行 SAA HITL 暂停点；恢复路径不重新装配上下文。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter) { invokeResume(turn, feedback, cwd, emitter, strategy.defaultRunPolicy()); }
    /** 人工审批完成后，按原 turn 的权限快照恢复执行。 */
    public void invokeResume(Turn turn, InterruptionMetadata feedback, String cwd, ItemEmitter emitter, AgentRunPolicy runPolicy) { outputHandler.invokeResume(turn, feedback, cwd, emitter, runPolicy); }
    /** 身份失效时清除不能再恢复的 ReactAgent 暂停现场。 */
    public void forgetPaused(String threadId) { outputHandler.forgetPaused(threadId); }
    private ReactAgent buildAgent(String providerId, String cwd, ItemEmitter emitter, TurnObservationContext context, AgentRunPolicy runPolicy, CapabilityExposurePlan exposurePlan) { return exposurePlan == null ? strategy.buildAgent(providerId, cwd, emitter, context, runPolicy) : strategy.buildAgent(providerId, cwd, emitter, context, runPolicy, exposurePlan); }
    private ContextWindowRuntimeResult prepareContextInput(Turn turn, String userText, String providerId, String cwd, AgentRunPolicy runPolicy, ItemEmitter emitter, CapabilityExposurePlan exposurePlan, java.util.List<com.wzx.babiq.server.attachment.AttachmentTextSegment> attachmentTextSegments) {
        if (contextWindowRuntime == null) return ContextWindowRuntimeResult.prepared(null, userText, userText);
        return contextWindowRuntime.prepare(new ContextWindowRuntimeInput(turn.threadId(), turn.id(), userText, providerId,
                strategy.resolveModelName(providerId), cwd, projectId(cwd), runPolicy, strategy.resolveContextWindow(providerId),
                strategy.currentToolCallbacks(exposurePlan), emitter, turn.businessIdentityScope(), attachmentTextSegments));
    }
    private void recordContextUsage(ContextWindowRuntimeResult contextInput, TurnObservationContext context) {
        if (contextWindowRuntime != null && contextInput != null) contextWindowRuntime.recordUsage(contextInput.snapshotId(), context);
    }
    private static String projectId(String cwd) { return cwd == null || cwd.isBlank() ? null : java.nio.file.Path.of(cwd).getFileName().toString(); }
}
