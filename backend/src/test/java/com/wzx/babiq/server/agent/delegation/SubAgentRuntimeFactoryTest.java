package com.wzx.babiq.server.agent.delegation;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子 Agent 运行时上下文装配测试。
 *
 * <p>Spring AI Alibaba 的 AgentTool 会复制父 RunnableConfig metadata 后清空 context。
 * BaBiQ 必须把 cwd、沙箱模式、观测上下文和 delegation id 同时放进 metadata 与
 * ToolContext，确保子 Agent 工具链还能复用现有横切层。</p>
 */
class SubAgentRuntimeFactoryTest {

    @Test
    void withDelegationContext_should_preserve_parent_metadata_and_attach_delegation() {
        TurnObservationContext observation = TurnObservationContext.start(
                "thr_parent", "turn_parent", "deepseek", "deepseek-v4-pro");
        RunnableConfig parentConfig = RunnableConfig.builder()
                .threadId("thr_parent")
                .addMetadata(TurnObservationContext.METADATA_KEY, observation)
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_CWD, "E:\\BaBiQ")
                .addMetadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, "DANGER_FULL_ACCESS")
                .build();
        SubAgentDelegationContext delegation = SubAgentDelegationContext.started(
                "it_delegate_1",
                "dlg_1",
                BuiltInSubAgents.MAIN_AGENT_NAME,
                "explorer",
                BabiqAgentMode.READ_ONLY_TOOL,
                null,
                observation);
        ToolContext original = new ToolContext(Map.of(SubAgentRuntimeFactory.AGENT_CONFIG_KEY, parentConfig));

        ToolContext enriched = SubAgentRuntimeFactory.withDelegationContext(original, delegation);

        assertThat(enriched.getContext()).containsEntry(SubAgentDelegationContext.METADATA_KEY, delegation);
        assertThat(enriched.getContext()).containsEntry(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, "READ_ONLY");
        RunnableConfig childConfig = (RunnableConfig) enriched.getContext().get(SubAgentRuntimeFactory.AGENT_CONFIG_KEY);
        assertThat(childConfig).isNotSameAs(parentConfig);
        assertThat(childConfig.metadata(SubAgentDelegationContext.METADATA_KEY)).contains(delegation);
        assertThat(childConfig.metadata(TurnObservationContext.METADATA_KEY)).contains(observation);
        assertThat(childConfig.metadata(BaBiQSandboxInterceptor.CONTEXT_CWD)).contains("E:\\BaBiQ");
        assertThat(childConfig.metadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE)).contains("READ_ONLY");
    }
}
