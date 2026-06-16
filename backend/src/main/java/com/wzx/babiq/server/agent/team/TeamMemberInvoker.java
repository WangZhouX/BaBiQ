package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 团队成员调用端口。
 *
 * <p>生产实现必须薄封装官方 {@link com.alibaba.cloud.ai.graph.agent.AgentTool}；
 * 测试实现只负责记录成员收到的指令文本。</p>
 */
public interface TeamMemberInvoker {

    /**
     * 调用一个团队成员 Agent，并返回最终文本。
     */
    String invoke(ReactAgent agent, String input, ToolContext toolContext);
}
