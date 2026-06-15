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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FlowOrchestrationToolWorkUnitTest {

    @Test
    void compact_flow_completion_summary_should_extract_node_outputs_from_overall_state_json() {
        String rawSummary = """
                {"OverAllState":{"data":{
                  "node_1_output":{"text":"已修改 H:\\\\aaa\\\\index.html，内容为：你好"},
                  "node_2_output":{"text":"已创建 H:\\\\aaa\\\\111.html，内容为：大家好"}
                }}}
                """;

        String summary = FlowOrchestrationTool.compactFlowCompletionSummary(rawSummary);

        assertThat(summary)
                .contains("index.html", "你好", "111.html", "大家好")
                .doesNotContain("OverAllState", "node_1_output", "node_2_output");
    }

    @Test
    void compact_flow_completion_summary_should_limit_verbose_node_outputs() {
        String verbose = "节点执行完成。".repeat(80);
        String rawSummary = """
                {"OverAllState":{"data":{
                  "node_1_output":{"text":"%s"}
                }}}
                """.formatted(verbose);

        String summary = FlowOrchestrationTool.compactFlowCompletionSummary(rawSummary);

        assertThat(summary).hasSizeLessThanOrEqualTo(260);
        assertThat(summary).endsWith("...");
    }

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
        when(agent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.empty());
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
    void orchestrate_flow_should_refuse_team_work_unit_goal() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        doThrow(new IllegalStateException("WorkUnit kind mismatch: expected orchestration but was team"))
                .when(workUnitService).requireGoalKind("goal_team_1", "orchestration");
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        String output = tool.orchestrateFlow("do not run flow for team", "sequential", List.of(), toolContext("goal_team_1"));

        assertThat(output).contains("WorkUnit", "orchestration", "team");
        verify(workUnitService).requireGoalKind("goal_team_1", "orchestration");
        verifyNoMoreInteractions(workUnitService);
        verifyNoInteractions(flowService, repository);
    }

    @Test
    void orchestrate_flow_should_pass_sandbox_metadata_to_nested_flow_config() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.empty());
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        tool.orchestrateFlow("整理登录页", "sequential", List.of(), writableToolContextWithParentConfig("goal_flow_3"));

        ArgumentCaptor<ToolContext> toolContextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        verify(flowService).buildOfficialFlowAgent(any(BabiqFlowSpec.class), toolContextCaptor.capture(), isNull());
        verify(agent).invoke(anyString(), configCaptor.capture());
        assertThat(toolContextCaptor.getValue().getContext())
                .containsEntry(BaBiQSandboxInterceptor.CONTEXT_CWD, "H:\\aaa")
                .containsEntry(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.WORKSPACE_WRITE.name())
                .containsKey(SubAgentRuntimeFactory.AGENT_CONFIG_KEY);
        assertThat(configCaptor.getValue().metadata(BaBiQSandboxInterceptor.CONTEXT_CWD)).contains("H:\\aaa");
        assertThat(configCaptor.getValue().metadata(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE))
                .contains(SandboxMode.WORKSPACE_WRITE.name());
        assertThat(configCaptor.getValue().metadata(BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS))
                .contains(List.of("H:\\aaa"));
    }

    @Test
    void orchestrate_flow_should_not_inherit_parent_checkpoint_resume_config() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString(), any(RunnableConfig.class))).thenReturn(Optional.empty());
        FlowOrchestrationTool tool = new FlowOrchestrationTool(
                flowService,
                repository,
                new FlowApprovalService(),
                workUnitService);

        tool.orchestrateFlow("整理登录页", "sequential", List.of(),
                toolContextWithParentResumeConfig("goal_flow_resume"));

        ArgumentCaptor<BabiqFlowSpec> specCaptor = ArgumentCaptor.forClass(BabiqFlowSpec.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        verify(flowService).buildOfficialFlowAgent(specCaptor.capture(), any(ToolContext.class), isNull());
        verify(agent).invoke(anyString(), configCaptor.capture());
        RunnableConfig config = configCaptor.getValue();
        assertThat(config.threadId()).contains(specCaptor.getValue().orchestrationId());
        assertThat(config.checkPointId()).isEmpty();
        assertThat(config.nextNode()).isEmpty();
        assertThat(config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY)).isEmpty();
    }

    @Test
    void orchestrate_flow_should_mark_work_unit_goal_failed_when_flow_fails() throws Exception {
        FlowOrchestrationService flowService = mock(FlowOrchestrationService.class);
        OrchestrationRepository repository = mock(OrchestrationRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        Agent agent = mock(Agent.class);
        when(flowService.buildOfficialFlowAgent(any(BabiqFlowSpec.class), any(ToolContext.class), isNull()))
                .thenReturn(agent);
        when(agent.invoke(anyString(), any(RunnableConfig.class))).thenThrow(new RuntimeException("boom"));
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

    private ToolContext writableToolContextWithParentConfig(String goalId) {
        return new ToolContext(Map.of(
                WorkUnitContextKeys.GOAL_ID, goalId,
                SubAgentRuntimeFactory.AGENT_CONFIG_KEY, RunnableConfig.builder().threadId("thr_parent").build(),
                BaBiQSandboxInterceptor.CONTEXT_CWD, "H:\\aaa",
                BaBiQSandboxInterceptor.CONTEXT_WRITABLE_ROOTS, List.of("H:\\aaa"),
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.WORKSPACE_WRITE.name()));
    }

    private ToolContext toolContextWithParentResumeConfig(String goalId) {
        return new ToolContext(Map.of(
                WorkUnitContextKeys.GOAL_ID, goalId,
                SubAgentRuntimeFactory.AGENT_CONFIG_KEY, RunnableConfig.builder()
                        .threadId("thr_parent")
                        .checkPointId("cp_parent")
                        .nextNode("node_parent")
                        .resume()
                        .build(),
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }

    private ToolContext noWorkUnitContext() {
        return new ToolContext(Map.of(
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }
}
