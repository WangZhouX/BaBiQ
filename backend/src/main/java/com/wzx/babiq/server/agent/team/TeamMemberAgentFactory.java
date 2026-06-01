package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 团队成员到官方 ReactAgent 的工厂端口。
 *
 * <p>生产实现复用 {@link com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory}；
 * 测试可替换该端口，避免为了验证 supervisor graph 装配而真实调用模型。</p>
 */
public interface TeamMemberAgentFactory {

    /**
     * 创建可被 supervisor StateGraph 调度的团队成员 ReactAgent。
     */
    ReactAgent create(BabiqTeamMember member,
                      String teamGoal,
                      ToolContext toolContext,
                      BaseCheckpointSaver sharedSaver,
                      CompileConfig compileConfig);
}
