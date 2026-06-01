package com.wzx.babiq.server.agent.flow;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 生产环境的流程节点 Agent 工厂。
 *
 * <p>它复用 P6-1 的 {@link SubAgentRuntimeFactory} 来构建 ReactAgent，
 * 因而节点工具调用仍然走 BaBiQ 既有的沙箱、Spotlighting、工具观测和 token 统计。
 * P6-2 没有另起一套执行器。</p>
 */
@Component
public class DefaultFlowNodeAgentFactory implements FlowNodeAgentFactory {

    /** 子 Agent 运行时工厂，负责薄封装 Spring AI Alibaba ReactAgent。 */
    private final SubAgentRuntimeFactory runtimeFactory;

    /**
     * 创建默认节点工厂。
     */
    public DefaultFlowNodeAgentFactory(SubAgentRuntimeFactory runtimeFactory) {
        this.runtimeFactory = runtimeFactory;
    }

    @Override
    public Agent create(BabiqFlowNode node, ToolContext toolContext) {
        TurnObservationContext observation = readObservation(toolContext);
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_flow_" + node.nodeId(),
                "dlg_flow_" + node.nodeId(),
                BuiltInSubAgents.MAIN_AGENT_NAME,
                node.name(),
                node.mode(),
                null,
                observation);
        ToolContext childContext = SubAgentRuntimeFactory.withDelegationContext(
                toolContext,
                delegation,
                readSandboxMode(toolContext));
        return runtimeFactory.buildChildAgentForFlow(node.toAgentSpec(), childContext, node.outputKey());
    }

    private TurnObservationContext readObservation(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(TurnObservationContext.METADATA_KEY);
        return value instanceof TurnObservationContext context ? context : null;
    }

    private SandboxMode readSandboxMode(ToolContext toolContext) {
        if (toolContext == null) {
            return SandboxMode.READ_ONLY;
        }
        Object value = toolContext.getContext().get(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE);
        if (value instanceof String text) {
            try {
                return SandboxMode.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                return SandboxMode.READ_ONLY;
            }
        }
        return SandboxMode.READ_ONLY;
    }
}
