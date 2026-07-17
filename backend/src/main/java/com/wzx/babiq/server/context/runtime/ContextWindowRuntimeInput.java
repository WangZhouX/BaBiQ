package com.wzx.babiq.server.context.runtime;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.ItemEmitter;
import org.springframework.ai.tool.ToolCallback;

/**
 * ContextWindowRuntime 的单轮输入。
 *
 * @param threadId 当前会话 id。
 * @param turnId 当前 turn id。
 * @param userText 本轮用户原始输入。
 * @param providerId 本轮 Provider id。
 * @param model 本轮模型名。
 * @param cwd 本轮工作目录。
 * @param projectId 当前项目 id，通常可用项目名或 cwd 派生。
 * @param runPolicy 本轮沙箱和审批策略快照。
 * @param modelContextWindow 当前模型上下文窗口 token 数。
 * @param toolCallbacks 当前候选工具 callback，用于生成能力目录摘要。
 * @param emitter 当前 WebSocket item 发射器；为空时只做后端审计，不推送压缩事件 item。
 */
public record ContextWindowRuntimeInput(
        String threadId,
        String turnId,
        String userText,
        String providerId,
        String model,
        String cwd,
        String projectId,
        AgentRunPolicy runPolicy,
        int modelContextWindow,
        ToolCallback[] toolCallbacks,
        ItemEmitter emitter,
        BusinessIdentityScope businessIdentityScope
) {
    /**
     * 兼容旧测试和旧调用点：没有 emitter 时仍可正常装配上下文。
     */
    public ContextWindowRuntimeInput(String threadId,
                                     String turnId,
                                     String userText,
                                     String providerId,
                                     String model,
                                     String cwd,
                                     String projectId,
                                     AgentRunPolicy runPolicy,
                                     int modelContextWindow,
                                     ToolCallback[] toolCallbacks) {
        this(threadId, turnId, userText, providerId, model, cwd, projectId,
                runPolicy, modelContextWindow, toolCallbacks, null, BusinessIdentityScope.UNSCOPED);
    }

    /** 兼容 Task 25 之前已携带 emitter 的调用点。 */
    public ContextWindowRuntimeInput(String threadId, String turnId, String userText, String providerId,
                                     String model, String cwd, String projectId, AgentRunPolicy runPolicy,
                                     int modelContextWindow, ToolCallback[] toolCallbacks, ItemEmitter emitter) {
        this(threadId, turnId, userText, providerId, model, cwd, projectId, runPolicy,
                modelContextWindow, toolCallbacks, emitter, BusinessIdentityScope.UNSCOPED);
    }

    public ContextWindowRuntimeInput {
        runPolicy = runPolicy == null ? new AgentRunPolicy(null, null) : runPolicy;
        toolCallbacks = toolCallbacks == null ? new ToolCallback[0] : toolCallbacks.clone();
        businessIdentityScope = businessIdentityScope == null
                ? BusinessIdentityScope.UNSCOPED
                : businessIdentityScope;
    }

    /** record 默认数组访问器会暴露内部引用，这里继续防御复制以保持输入快照不可变。 */
    @Override
    public ToolCallback[] toolCallbacks() {
        return toolCallbacks.clone();
    }
}
