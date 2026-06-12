package com.wzx.babiq.server.tool.impl;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.conversation.ItemEmitter;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import com.wzx.babiq.server.interceptor.BaBiQSandboxInterceptor;
import com.wzx.babiq.server.observability.TurnObservationContext;
import com.wzx.babiq.server.tool.ToolResult;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitContextKeys;
import com.wzx.babiq.server.workunit.WorkUnitCreateRequest;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkUnitManageToolTest {

    @Test
    void append_goal_should_create_or_reuse_work_unit_and_emit_item() throws Exception {
        WorkUnitService service = mock(WorkUnitService.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        WorkUnitItem item = new WorkUnitItem(
                "it_workunit_1",
                "workUnit",
                "wu_1",
                "team",
                "前端验收组",
                "waiting_config",
                "goal_1",
                "检查设置页",
                1,
                null);
        when(service.createOrAppend(any(), any(), any(), any(), any())).thenReturn(item);

        WorkUnitManageTool tool = new WorkUnitManageTool(service);
        ToolResult result = tool.manage(
                "append_goal",
                "team",
                "前端验收组",
                "检查设置页",
                null,
                null,
                toolContext(emitter));

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("wu_1", "goal_1");
        ArgumentCaptor<WorkUnitCreateRequest> requestCaptor = ArgumentCaptor.forClass(WorkUnitCreateRequest.class);
        verify(service).createOrAppend(
                requestCaptor.capture(),
                argThat(thread -> "thr_manage".equals(thread.id())),
                argThat(turn -> "turn_manage".equals(turn.id())),
                org.mockito.ArgumentMatchers.eq("H:\\aaa"),
                any(AgentRunPolicy.class));
        assertThat(requestCaptor.getValue().kind()).isEqualTo("team");
        assertThat(requestCaptor.getValue().name()).isEqualTo("前端验收组");
        assertThat(requestCaptor.getValue().goal()).isEqualTo("检查设置页");
        verify(emitter).emitItemAdded(item);
    }

    @Test
    void start_should_bind_pending_goal_to_current_tool_context_without_running_any_flow() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit workUnit = workUnit("wu_1", "team", "前端验收组", "waiting_config", "goal_1");
        WorkUnitGoal goal = goal("goal_1", "wu_1", "检查设置页", "pending");
        when(service.listVisible("thr_manage")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(goal));
        Map<String, Object> context = baseContext(null);

        WorkUnitManageTool tool = new WorkUnitManageTool(service);
        ToolResult result = tool.manage(
                "start",
                "team",
                "前端验收组",
                null,
                null,
                null,
                new ToolContext(context));

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("coordinate_team", "goal_1");
        assertThat(WorkUnitContextKeys.goalId(new ToolContext(context))).isEqualTo("goal_1");
    }

    @Test
    void update_goal_should_modify_pending_goal_and_emit_refreshed_item() throws Exception {
        WorkUnitService service = mock(WorkUnitService.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        WorkUnit workUnit = workUnit("wu_1", "orchestration", "html-test", "waiting_config", "goal_1");
        WorkUnitGoal pendingGoal = goal("goal_1", "wu_1", "old goal", "pending");
        WorkUnitGoal updatedGoal = goal("goal_1", "wu_1", "new configured goal", "pending");
        WorkUnitItem refreshedItem = new WorkUnitItem(
                "it_workunit_1",
                "workUnit",
                "wu_1",
                "orchestration",
                "html-test",
                "waiting_config",
                "goal_1",
                "new configured goal",
                1,
                null);
        when(service.listVisible("thr_manage")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(pendingGoal));
        when(service.updateGoal("goal_1", "new configured goal")).thenReturn(updatedGoal);
        when(service.itemFor(workUnit)).thenReturn(refreshedItem);

        WorkUnitManageTool tool = new WorkUnitManageTool(service);
        ToolResult result = tool.manage(
                "update_goal",
                "orchestration",
                "html-test",
                "new configured goal",
                null,
                null,
                toolContext(emitter));

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("goal_1", "new configured goal");
        verify(service).updateGoal("goal_1", "new configured goal");
        verify(emitter).emitItemUpdated(refreshedItem);
    }

    @Test
    void remove_should_require_explicit_confirmation_before_soft_remove() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit workUnit = workUnit("wu_1", "orchestration", "登录页重构", "completed", "goal_1");
        when(service.listVisible("thr_manage")).thenReturn(List.of(workUnit));

        WorkUnitManageTool tool = new WorkUnitManageTool(service);
        ToolResult result = tool.manage(
                "remove",
                null,
                null,
                null,
                "wu_1",
                null,
                toolContext(null));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("二次确认", "confirmed=true", "wu_1");
        verify(service, never()).remove(anyString());
    }

    @Test
    void remove_should_soft_remove_work_unit_after_confirmation_and_emit_removed_item() throws Exception {
        WorkUnitService service = mock(WorkUnitService.class);
        ItemEmitter emitter = mock(ItemEmitter.class);
        WorkUnit workUnit = workUnit("wu_1", "orchestration", "登录页重构", "completed", "goal_1");
        WorkUnit removed = workUnit("wu_1", "orchestration", "登录页重构", "removed", "goal_1");
        WorkUnitItem removedItem = new WorkUnitItem(
                "it_workunit_1",
                "workUnit",
                "wu_1",
                "orchestration",
                "登录页重构",
                "removed",
                "goal_1",
                "拆分登录页流程",
                1,
                null);
        when(service.listVisible("thr_manage")).thenReturn(List.of(workUnit));
        when(service.remove("wu_1")).thenReturn(removed);
        when(service.itemFor(removed)).thenReturn(removedItem);

        WorkUnitManageTool tool = new WorkUnitManageTool(service);
        ToolResult result = tool.manage(
                "remove",
                null,
                null,
                null,
                "wu_1",
                true,
                toolContext(emitter));

        assertThat(result.ok()).isTrue();
        assertThat(result.output()).contains("已移除", "wu_1");
        verify(service).remove("wu_1");
        verify(emitter).emitItemUpdated(removedItem);
    }

    @Test
    void missing_runtime_context_should_fail_fast() {
        WorkUnitManageTool tool = new WorkUnitManageTool(mock(WorkUnitService.class));

        ToolResult result = tool.manage("append_goal", "team", "测试组", "目标", null, null, new ToolContext(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("缺少");
    }

    private ToolContext toolContext(ItemEmitter emitter) {
        return new ToolContext(baseContext(emitter));
    }

    private Map<String, Object> baseContext(ItemEmitter emitter) {
        Map<String, Object> context = new HashMap<>();
        if (emitter != null) {
            context.put(BaBiQSandboxInterceptor.CONTEXT_ITEM_EMITTER, emitter);
        }
        context.put(BaBiQSandboxInterceptor.CONTEXT_CWD, "H:\\aaa");
        context.put(BaBiQSandboxInterceptor.CONTEXT_SANDBOX_MODE, "WORKSPACE_WRITE");
        context.put(TurnObservationContext.METADATA_KEY,
                TurnObservationContext.start("thr_manage", "turn_manage", "provider", "model", () -> 0L));
        return context;
    }

    private WorkUnit workUnit(String id, String kind, String name, String status, String currentGoalId) {
        Instant now = Instant.parse("2026-06-02T01:00:00Z");
        return new WorkUnit(
                id,
                "thr_manage",
                kind,
                name,
                name,
                status,
                currentGoalId,
                "H:\\aaa",
                "WORKSPACE_WRITE",
                "removed".equals(status),
                null,
                now,
                now);
    }

    private WorkUnitGoal goal(String id, String workUnitId, String goalText, String status) {
        Instant now = Instant.parse("2026-06-02T01:00:00Z");
        return new WorkUnitGoal(
                id,
                workUnitId,
                "thr_manage",
                goalText,
                status,
                null,
                null,
                null,
                null,
                now,
                null,
                null);
    }
}
