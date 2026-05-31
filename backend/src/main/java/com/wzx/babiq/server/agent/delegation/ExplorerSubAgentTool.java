package com.wzx.babiq.server.agent.delegation;

import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.tool.Tool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 父 Agent 可调用的 explorer 委派入口。
 *
 * <p>该类本身只是 Spring AI 本地工具适配层；真正的子 Agent 执行由
 * {@link SubAgentRuntimeFactory} 使用 Spring AI Alibaba 官方 AgentTool 完成。</p>
 */
@Component
public class ExplorerSubAgentTool implements Tool {

    /** 负责构建 explorer 子 Agent 并调用官方 AgentTool。 */
    private final SubAgentRuntimeFactory runtimeFactory;

    public ExplorerSubAgentTool(SubAgentRuntimeFactory runtimeFactory) {
        this.runtimeFactory = runtimeFactory;
    }

    @Override
    public String name() {
        return BuiltInSubAgents.EXPLORER_NAME;
    }

    /**
     * 委派只读探索任务给 explorer。
     *
     * @param input 父 Agent 传入的一句话探索任务
     * @param toolContext Spring AI 运行态上下文，携带 cwd、emitter、turn 观测和父 RunnableConfig
     * @return explorer 的最终摘要，返回给父 Agent 继续综合
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = BuiltInSubAgents.EXPLORER_NAME,
            description = "Delegate a focused read-only repository exploration task to explorer. 委派只读子 Agent 探索代码、读取文件、列出目录和搜索关键词。"
    )
    public String explore(String input, ToolContext toolContext) {
        TurnObservationContext observation = observation(toolContext);
        ItemEmitter emitter = emitter(toolContext);
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                newItemId(),
                newDelegationId(),
                BuiltInSubAgents.MAIN_AGENT_NAME,
                BuiltInSubAgents.EXPLORER_NAME,
                BabiqAgentMode.READ_ONLY_TOOL,
                emitter,
                observation);
        delegation.emitStarted(input == null || input.isBlank() ? "开始只读探索" : input);
        try {
            String result = runtimeFactory.delegate(BuiltInSubAgents.explorer(), input, toolContext, delegation);
            delegation.emitCompleted(preview(result));
            return result;
        } catch (RuntimeException exception) {
            delegation.emitFailed(preview(exception.getMessage()));
            throw exception;
        }
    }

    private TurnObservationContext observation(ToolContext toolContext) {
        Object candidate = toolContext == null ? null : toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return candidate instanceof TurnObservationContext context ? context : null;
    }

    private ItemEmitter emitter(ToolContext toolContext) {
        Object candidate = toolContext == null ? null : toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER);
        return candidate instanceof ItemEmitter itemEmitter ? itemEmitter : null;
    }

    private String newItemId() {
        return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String newDelegationId() {
        return "dlg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 600 ? value : value.substring(0, 600) + "\n...[truncated]";
    }
}
