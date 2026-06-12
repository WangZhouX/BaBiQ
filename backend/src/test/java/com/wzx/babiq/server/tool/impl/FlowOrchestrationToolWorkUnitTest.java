package com.wzx.babiq.server.tool.impl;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.wzx.babiq.server.agent.delegation.SubAgentRuntimeFactory;
import com.wzx.babiq.server.agent.flow.BabiqFlowSpec;
import com.wzx.babiq.server.agent.flow.FlowApprovalService;
import com.wzx.babiq.server.agent.flow.FlowOrchestrationService;
import com.wzx.babiq.server.agent.flow.OrchestrationRepository;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.workunit.WorkUnitContextKeys;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FlowOrchestrationToolWorkUnitTest {

    @Test
    void orchestrate_flow_should_refuse_without_work_unit_start_context() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        String output = tool.orchestrateFlow("整理登录页", "sequential", List.of(), noWorkUnitContext());

        assertThat(output).contains("WorkUnit", "工作容器", "右侧详情页", "启动");
        verifyNoInteractions(flowService, repository, workUnitService);
    }

    @Test
    void orchestrate_flow_should_link_current_work_unit_goal() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString())).thenReturn(Optional.empty());
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        String output = tool.orchestrateFlow("整理登录页", "sequential", List.of(), toolContext("goal_flow_1"));

        assertThat(output).isNotBlank();
        verify(workUnitService).markGoalRunning(eq("goal_flow_1"), eq("orchestration"), startsWith("orch_"));
        verify(workUnitService).markGoalCompleted("goal_flow_1", output);
    }

    @Test
    void orchestrate_flow_should_not_reuse_parent_runnable_config_for_nested_flow() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString())).thenReturn(Optional.empty());
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        tool.orchestrateFlow("整理登录页", "sequential", List.of(), toolContextWithParentConfig("goal_flow_3"));

        verify(agent).invoke(anyString());
        verify(agent, never()).invoke(anyString(), any(RunnableConfig.class));
    }

    @Test
    void orchestrate_flow_should_mark_work_unit_goal_failed_when_flow_fails() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString())).thenThrow(new RuntimeException("boom"));
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        String output = tool.orchestrateFlow("整理登录页", "sequential", List.of(), toolContext("goal_flow_2"));

        assertThat(output).contains("boom");
        verify(workUnitService).markGoalRunning(eq("goal_flow_2"), eq("orchestration"), startsWith("orch_"));
        verify(workUnitService).markGoalFailed("goal_flow_2", "boom");
    }

    private ToolContext toolContext(String goalId) {
        return new ToolContext(Map.of(
                WorkUnitContextKeys.GOAL_ID, goalId,
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }

    private ToolContext toolContextWithParentConfig(String goalId) {
        return new ToolContext(Map.of(
                WorkUnitContextKeys.GOAL_ID, goalId,
                SubAgentRuntimeFactory.AGENT_CONFIG_KEY, RunnableConfig.builder().threadId("thr_parent").build(),
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }

    private ToolContext noWorkUnitContext() {
        return new ToolContext(Map.of(
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }
}
