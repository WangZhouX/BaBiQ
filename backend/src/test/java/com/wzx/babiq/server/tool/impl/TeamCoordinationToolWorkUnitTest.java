package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.agent.team.BabiqTeamSpec;
import com.wzx.babiq.server.agent.team.TeamApprovalService;
import com.wzx.babiq.server.agent.team.TeamCoordinationService;
import com.wzx.babiq.server.agent.team.TeamExecutionResult;
import com.wzx.babiq.server.agent.team.TeamRepository;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.sandbox.SandboxMode;
import com.wzx.babiq.server.workunit.WorkUnitContextKeys;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TeamCoordinationToolWorkUnitTest {

    @Test
    void coordinate_team_should_refuse_without_work_unit_start_context() {
        TeamCoordinationService coordinationService = mock(TeamCoordinationService.class);
        TeamRepository repository = mock(TeamRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        TeamCoordinationTool tool = new TeamCoordinationTool(
                coordinationService,
                repository,
                new TeamApprovalService(),
                workUnitService);

        String output = tool.coordinateTeam("梳理登录页", List.of(), 2, noWorkUnitContext());

        assertThat(output).contains("WorkUnit", "工作容器", "右侧详情页", "启动");
        verifyNoInteractions(coordinationService, repository, workUnitService);
    }

    @Test
    void coordinate_team_should_link_current_work_unit_goal() {
        TeamCoordinationService coordinationService = mock(TeamCoordinationService.class);
        TeamRepository repository = mock(TeamRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        when(coordinationService.run(any(BabiqTeamSpec.class), any(ToolContext.class)))
                .thenReturn(new TeamExecutionResult("completed", "团队已完成", 1, "explorer"));
        TeamCoordinationTool tool = new TeamCoordinationTool(
                coordinationService,
                repository,
                new TeamApprovalService(),
                workUnitService);

        String output = tool.coordinateTeam("梳理登录页", List.of(), 2, toolContext("goal_team_1"));

        assertThat(output).isEqualTo("团队已完成");
        verify(workUnitService).markGoalRunning(eq("goal_team_1"), eq("team"), startsWith("team_"));
        verify(workUnitService).markGoalCompleted("goal_team_1", "团队已完成");
    }

    @Test
    void coordinate_team_should_refuse_orchestration_work_unit_goal() {
        TeamCoordinationService coordinationService = mock(TeamCoordinationService.class);
        TeamRepository repository = mock(TeamRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        doThrow(new IllegalStateException("WorkUnit kind mismatch: expected team but was orchestration"))
                .when(workUnitService).requireGoalKind("goal_flow_1", "team");
        TeamCoordinationTool tool = new TeamCoordinationTool(
                coordinationService,
                repository,
                new TeamApprovalService(),
                workUnitService);

        String output = tool.coordinateTeam("do not fallback to team", List.of(), 2, toolContext("goal_flow_1"));

        assertThat(output).contains("WorkUnit", "team", "orchestration");
        verify(workUnitService).requireGoalKind("goal_flow_1", "team");
        verifyNoMoreInteractions(workUnitService);
        verifyNoInteractions(coordinationService, repository);
    }

    @Test
    void coordinate_team_should_mark_work_unit_goal_failed_when_team_fails() {
        TeamCoordinationService coordinationService = mock(TeamCoordinationService.class);
        TeamRepository repository = mock(TeamRepository.class);
        WorkUnitService workUnitService = mock(WorkUnitService.class);
        when(coordinationService.run(any(BabiqTeamSpec.class), any(ToolContext.class)))
                .thenReturn(new TeamExecutionResult("failed", "成员执行失败", 1, "writer"));
        TeamCoordinationTool tool = new TeamCoordinationTool(
                coordinationService,
                repository,
                new TeamApprovalService(),
                workUnitService);

        String output = tool.coordinateTeam("梳理登录页", List.of(), 2, toolContext("goal_team_2"));

        assertThat(output).isEqualTo("成员执行失败");
        verify(workUnitService).markGoalRunning(eq("goal_team_2"), eq("team"), startsWith("team_"));
        verify(workUnitService).markGoalFailed("goal_team_2", "成员执行失败");
    }

    private ToolContext toolContext(String goalId) {
        return new ToolContext(Map.of(
                WorkUnitContextKeys.GOAL_ID, goalId,
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }

    private ToolContext noWorkUnitContext() {
        return new ToolContext(Map.of(
                BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, SandboxMode.READ_ONLY.name()));
    }
}
