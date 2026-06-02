package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.WorkUnitInfo;
import com.wzx.babiq.server.api.dto.WorkUnitListResult;
import com.wzx.babiq.server.api.dto.WorkUnitRemoveResult;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6-4 工作容器 JSON-RPC handler 测试。
 *
 * <p>桌面端只通过这些接口读取和移除编排/团队容器，不能绕过后端 WorkUnit 生命周期服务。</p>
 */
class WorkUnitHandlersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("workunit/list 返回可见容器和目标队列")
    void list_should_return_visible_work_units_with_goals() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit workUnit = sampleWorkUnit("wu_1", "orchestration", "登录页重构", "waiting_config", "goal_1", false);
        WorkUnitGoal goal = sampleGoal("goal_1", "wu_1", "拆分登录页改造流程", "pending");
        when(service.listVisible("thr_1")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(goal));

        Object result = new WorkUnitListHandler(service)
                .handle(objectMapper.valueToTree(Map.of("threadId", "thr_1")), null);

        assertThat(result).isInstanceOf(WorkUnitListResult.class);
        WorkUnitListResult list = (WorkUnitListResult) result;
        assertThat(list.workUnits()).hasSize(1);
        WorkUnitInfo info = list.workUnits().getFirst();
        assertThat(info.workUnitId()).isEqualTo("wu_1");
        assertThat(info.kind()).isEqualTo("orchestration");
        assertThat(info.name()).isEqualTo("登录页重构");
        assertThat(info.goals()).hasSize(1);
        assertThat(info.goals().getFirst().goalText()).isEqualTo("拆分登录页改造流程");
    }

    @Test
    @DisplayName("workunit/remove 软移除已完成容器")
    void remove_should_delegate_to_service_and_return_removed_work_unit() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit removed = sampleWorkUnit("wu_2", "team", "前端验收组", "removed", "goal_2", true);
        when(service.remove("wu_2")).thenReturn(removed);

        Object result = new WorkUnitRemoveHandler(service)
                .handle(objectMapper.valueToTree(Map.of("workUnitId", "wu_2")), null);

        assertThat(result).isEqualTo(new WorkUnitRemoveResult("wu_2", "team", "前端验收组", "removed", true));
        verify(service).remove("wu_2");
    }

    private static WorkUnit sampleWorkUnit(String workUnitId,
                                           String kind,
                                           String name,
                                           String status,
                                           String goalId,
                                           boolean removed) {
        return new WorkUnit(workUnitId, "thr_1", kind, name, name, status, goalId,
                "H:/aaa", "WORKSPACE_WRITE", removed,
                removed ? Instant.parse("2026-06-02T08:00:00Z") : null,
                Instant.parse("2026-06-02T07:00:00Z"),
                Instant.parse("2026-06-02T08:00:00Z"));
    }

    private static WorkUnitGoal sampleGoal(String goalId, String workUnitId, String goalText, String status) {
        return new WorkUnitGoal(goalId, workUnitId, "thr_1", goalText, status,
                null, null, null, null,
                Instant.parse("2026-06-02T07:00:00Z"),
                null,
                null);
    }
}
