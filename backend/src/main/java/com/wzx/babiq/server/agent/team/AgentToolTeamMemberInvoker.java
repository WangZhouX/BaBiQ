package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基于官方 AgentTool 的团队成员调用器。
 */
@Component
public class AgentToolTeamMemberInvoker implements TeamMemberInvoker {

    /** AgentTool 输入 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建成员调用器。
     */
    public AgentToolTeamMemberInvoker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String invoke(ReactAgent agent, String input, ToolContext toolContext) {
        try {
            ToolCallback callback = AgentTool.getFunctionToolCallback(agent);
            return callback.call(objectMapper.writeValueAsString(Map.of("input", input == null ? "" : input)),
                    toolContext);
        } catch (Exception exception) {
            throw new IllegalStateException("团队成员调用失败", exception);
        }
    }
}
