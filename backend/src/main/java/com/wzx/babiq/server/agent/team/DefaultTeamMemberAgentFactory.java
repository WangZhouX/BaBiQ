package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.wzx.babiq.server.agent.delegation.BuiltInSubAgents;
import com.wzx.babiq.server.agent.delegation.SubAgentDelegationContext;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * 生产环境的团队成员 Agent 工厂。
 *
 * <p>它复用 P6-1 的 {@link SubAgentRuntimeFactory} 构建 ReactAgent，同时把 P6-3
 * 的 shared saver/compileConfig 传入子 Agent。这样成员工具调用仍走 BaBiQ 沙箱、
 * Spotlighting、工具观测和 token 统计，图状态则交给 Spring AI Alibaba 官方机制。</p>
 */
@Component
public class DefaultTeamMemberAgentFactory implements TeamMemberAgentFactory {

    /** 子 Agent 运行时工厂，负责薄封装 Spring AI Alibaba ReactAgent。 */
    private final SubAgentRuntimeFactory runtimeFactory;

    /**
     * 创建默认团队成员工厂。
     */
    public DefaultTeamMemberAgentFactory(SubAgentRuntimeFactory runtimeFactory) {
        this.runtimeFactory = runtimeFactory;
    }

    /**
     * 测试友好重载：没有团队目标时使用空目标，便于单独验证 saver/config 透传。
     */
    public ReactAgent create(BabiqTeamMember member,
                             ToolContext toolContext,
                             BaseCheckpointSaver sharedSaver,
                             CompileConfig compileConfig) {
        return create(member, "", toolContext, sharedSaver, compileConfig);
    }

    @Override
    public ReactAgent create(BabiqTeamMember member,
                             String teamGoal,
                             ToolContext toolContext,
                             BaseCheckpointSaver sharedSaver,
                             CompileConfig compileConfig) {
        TurnObservationContext observation = readObservation(toolContext);
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_team_" + member.memberId(),
                "dlg_team_" + member.memberId(),
                BuiltInSubAgents.MAIN_AGENT_NAME,
                member.name(),
                member.mode(),
                null,
                observation);
        ToolContext childContext = SubAgentRuntimeFactory.withDelegationContext(
                toolContext,
                delegation,
                readSandboxMode(toolContext));
        return runtimeFactory.buildChildAgentForTeam(
                member.toAgentSpec(teamGoal),
                childContext,
                member.outputKey(),
                sharedSaver,
                compileConfig);
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
