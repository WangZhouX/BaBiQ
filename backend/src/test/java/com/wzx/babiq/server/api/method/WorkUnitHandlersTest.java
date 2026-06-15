package com.wzx.babiq.server.api.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.api.dto.WorkUnitInfo;
import com.wzx.babiq.server.api.dto.WorkUnitListResult;
import com.wzx.babiq.server.api.dto.WorkUnitConfigUpdateResult;
import com.wzx.babiq.server.api.dto.WorkUnitGoalUpdateResult;
import com.wzx.babiq.server.api.dto.WorkUnitRemoveResult;
import com.wzx.babiq.server.api.dto.WorkUnitNameUpdateResult;
import com.wzx.babiq.server.workunit.WorkUnit;
import com.wzx.babiq.server.workunit.WorkUnitConfig;
import com.wzx.babiq.server.workunit.WorkUnitGoal;
import com.wzx.babiq.server.workunit.WorkUnitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        WorkUnitConfig config = sampleConfig("wu_1", "{\"nodes\":[{\"id\":\"analyzer\",\"model\":\"qwen-plus\"}]}");
        when(service.listVisible("thr_1")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(goal));
        when(service.findConfig("wu_1")).thenReturn(java.util.Optional.of(config));

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
        assertThat(info.configJson()).contains("qwen-plus");
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

    @Test
    @DisplayName("workunit/name/update 修改容器名称并返回刷新后的容器")
    void update_name_should_delegate_to_service_and_return_refreshed_work_unit() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit existing = sampleWorkUnit("wu_1", "orchestration", "old-flow", "waiting_config", "goal_1", false);
        WorkUnit renamed = sampleWorkUnit("wu_1", "orchestration", "new-flow", "waiting_config", "goal_1", false);
        WorkUnitGoal goal = sampleGoal("goal_1", "wu_1", "edit html", "pending");
        when(service.listVisible("thr_1")).thenReturn(List.of(existing));
        when(service.rename("wu_1", "new-flow")).thenReturn(renamed);
        when(service.listGoals("wu_1")).thenReturn(List.of(goal));

        Object result = new WorkUnitNameUpdateHandler(service)
                .handle(objectMapper.valueToTree(Map.of(
                        "threadId", "thr_1",
                        "workUnitId", "wu_1",
                        "name", "new-flow"
                )), null);

        assertThat(result).isInstanceOf(WorkUnitNameUpdateResult.class);
        WorkUnitNameUpdateResult update = (WorkUnitNameUpdateResult) result;
        assertThat(update.workUnit().name()).isEqualTo("new-flow");
        assertThat(update.workUnit().goals()).hasSize(1);
        verify(service).rename("wu_1", "new-flow");
    }

    @Test
    @DisplayName("workunit/goal/update 鐩存帴淇濆瓨寰呮墽琛岀洰鏍囧苟杩斿洖鍒锋柊鍚庣殑瀹瑰櫒")
    void update_goal_should_delegate_to_service_and_return_refreshed_work_unit() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit workUnit = sampleWorkUnit("wu_1", "orchestration", "html测试", "waiting_config", "goal_1", false);
        WorkUnitGoal updatedGoal = sampleGoal("goal_1", "wu_1", "重新检查登录页样式", "pending");
        when(service.updateGoal("goal_1", "重新检查登录页样式")).thenReturn(updatedGoal);
        when(service.listVisible("thr_1")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(updatedGoal));

        Object result = new WorkUnitGoalUpdateHandler(service)
                .handle(objectMapper.valueToTree(Map.of(
                        "threadId", "thr_1",
                        "workUnitId", "wu_1",
                        "goalId", "goal_1",
                        "goalText", "重新检查登录页样式"
                )), null);

        assertThat(result).isInstanceOf(WorkUnitGoalUpdateResult.class);
        WorkUnitGoalUpdateResult update = (WorkUnitGoalUpdateResult) result;
        assertThat(update.updatedGoal().goalText()).isEqualTo("重新检查登录页样式");
        assertThat(update.workUnit().workUnitId()).isEqualTo("wu_1");
        assertThat(update.workUnit().goals().getFirst().goalText()).isEqualTo("重新检查登录页样式");
        verify(service).updateGoal("goal_1", "重新检查登录页样式");
    }

    @Test
    @DisplayName("workunit/config/update 保存配置快照并返回刷新后的容器")
    void update_config_should_persist_configuration_snapshot() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit workUnit = sampleWorkUnit("wu_1", "orchestration", "html测试", "waiting_config", "goal_1", false);
        WorkUnitGoal goal = sampleGoal("goal_1", "wu_1", "检查 html", "pending");
        String configJson = "{\"nodes\":[{\"id\":\"analyzer\",\"model\":\"provider:qwen:qwen-plus\"}]}";
        String structureJson = "{\"root\":{\"groupId\":\"g_root\",\"topology\":\"sequential\",\"children\":[{\"nodeId\":\"analyzer\"}]}}";
        when(service.listVisible("thr_1")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(goal));
        when(service.updateConfig("wu_1", configJson, structureJson)).thenReturn(sampleConfig("wu_1", configJson, structureJson));
        when(service.findConfig("wu_1")).thenReturn(java.util.Optional.of(sampleConfig("wu_1", configJson, structureJson)));

        Object result = new WorkUnitConfigUpdateHandler(service)
                .handle(objectMapper.valueToTree(Map.of(
                        "threadId", "thr_1",
                        "workUnitId", "wu_1",
                        "configJson", configJson,
                        "structureJson", structureJson
                )), null);

        assertThat(result).isInstanceOf(WorkUnitConfigUpdateResult.class);
        WorkUnitConfigUpdateResult update = (WorkUnitConfigUpdateResult) result;
        assertThat(update.workUnit().configJson()).contains("provider:qwen:qwen-plus");
        assertThat(update.workUnit().structureJson()).contains("\"groupId\":\"g_root\"");
        verify(service).updateConfig("wu_1", configJson, structureJson);
    }

    @Test
    @DisplayName("workunit/goal/update 在目标不属于当前容器时不执行保存")
    void update_goal_should_validate_goal_ownership_before_saving() {
        WorkUnitService service = mock(WorkUnitService.class);
        WorkUnit workUnit = sampleWorkUnit("wu_1", "orchestration", "html测试", "waiting_config", "goal_1", false);
        when(service.listVisible("thr_1")).thenReturn(List.of(workUnit));
        when(service.listGoals("wu_1")).thenReturn(List.of(sampleGoal("goal_2", "wu_1", "另一个目标", "pending")));

        assertThatThrownBy(() -> new WorkUnitGoalUpdateHandler(service)
                .handle(objectMapper.valueToTree(Map.of(
                        "threadId", "thr_1",
                        "workUnitId", "wu_1",
                        "goalId", "goal_999",
                        "goalText", "不应该保存"
                )), null))
                .hasMessageContaining("目标不属于当前工作容器");

        verify(service, never()).updateGoal(anyString(), anyString());
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

    private static WorkUnitConfig sampleConfig(String workUnitId, String configJson) {
        return sampleConfig(workUnitId, configJson, null);
    }

    private static WorkUnitConfig sampleConfig(String workUnitId, String configJson, String structureJson) {
        return new WorkUnitConfig(workUnitId, configJson, structureJson,
                Instant.parse("2026-06-02T07:00:00Z"),
                Instant.parse("2026-06-02T08:00:00Z"));
    }
}
