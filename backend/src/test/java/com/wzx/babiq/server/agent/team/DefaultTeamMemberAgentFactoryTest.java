package com.wzx.babiq.server.agent.team;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.wzx.babiq.server.agent.delegation.BabiqAgentMode;
import com.wzx.babiq.server.agent.delegation.BabiqAgentSpec;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队成员 Agent 工厂测试。
 *
 * <p>P6-3 明确要求 supervisor StateGraph 和所有 teammate ReactAgent 共享同一个
 * MemorySaver/CompileConfig，这样中断、恢复和检查点才在同一条官方图运行链路中。</p>
 */
class DefaultTeamMemberAgentFactoryTest {

    @Test
    void create_should_pass_shared_saver_and_compile_config_to_child_agent() {
        SubAgentRuntimeFactory runtimeFactory = mock(SubAgentRuntimeFactory.class);
        ReactAgent reactAgent = mock(ReactAgent.class);
        when(runtimeFactory.buildChildAgentForTeam(any(), any(), eq("explorer_output"), any(), any()))
                .thenReturn(reactAgent);
        DefaultTeamMemberAgentFactory factory = new DefaultTeamMemberAgentFactory(runtimeFactory);
        BaseCheckpointSaver saver = new MemorySaver();
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();

        factory.create(member(), new ToolContext(Map.of(
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.WORKSPACE_WRITE.name())),
                saver,
                compileConfig);

        verify(runtimeFactory).buildChildAgentForTeam(
                any(BabiqAgentSpec.class),
                any(ToolContext.class),
                eq("explorer_output"),
                same(saver),
                same(compileConfig));
    }

    private BabiqTeamMember member() {
        return new BabiqTeamMember(
                "member_explorer",
                "explorer",
                "探索成员",
                "explorer",
                "读取项目结构",
                List.of("read_file", "list_dir"),
                BabiqAgentSpec.ModelPolicy.inherit(),
                BabiqAgentMode.READ_ONLY_TOOL,
                1,
                "explorer_output",
                List.of());
    }
}
