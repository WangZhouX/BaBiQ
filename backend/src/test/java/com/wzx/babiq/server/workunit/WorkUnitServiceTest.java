package com.wzx.babiq.server.workunit;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.approval.ApprovalPolicy;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;
import com.wzx.babiq.server.sandbox.SandboxMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-4 工作容器服务测试。
 *
 * <p>Slash 命令路径必须由服务端确定性创建或复用容器并追加目标；这组测试用真实 SQLite/Flyway
 * 环境钉住状态机，避免后续误把 slash 变成自动执行 flow/team。</p>
 */
@SpringBootTest
class WorkUnitServiceTest {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "workunit-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private WorkUnitService service;

    @Test
    void create_or_append_should_create_container_and_goal_without_starting_execution() {
        Thread thread = Thread.newThread("thr_wu_1", "H:/aaa");
        Turn turn = new Turn("turn_wu_1", thread.id());

        WorkUnitItem item = service.createOrAppend(
                new WorkUnitCreateRequest("orchestration", "登录页重构", "拆分登录页改造流程", null),
                thread,
                turn,
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));

        assertThat(item.kind()).isEqualTo("orchestration");
        assertThat(item.name()).isEqualTo("登录页重构");
        assertThat(item.status()).isEqualTo("waiting_config");
        assertThat(item.activeGoal()).isEqualTo("拆分登录页改造流程");
        assertThat(item.goalCount()).isEqualTo(1);
        assertThat(item.linkedRunId()).isNull();
        assertThat(service.listVisible(thread.id())).extracting(WorkUnit::workUnitId)
                .containsExactly(item.workUnitId());
    }

    @Test
    void completed_container_should_be_reused_for_next_goal() {
        Thread thread = Thread.newThread("thr_wu_2", "H:/aaa");
        Turn turn = new Turn("turn_wu_2", thread.id());
        WorkUnitItem first = service.createOrAppend(
                new WorkUnitCreateRequest("team", "前端验收组", "检查聊天页", null),
                thread,
                turn,
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
        service.markGoalCompleted(first.activeGoalId(), "聊天页检查完成");

        WorkUnitItem second = service.createOrAppend(
                new WorkUnitCreateRequest("team", "前端验收组", "继续检查技能页", null),
                thread,
                new Turn("turn_wu_2b", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));

        assertThat(second.workUnitId()).isEqualTo(first.workUnitId());
        assertThat(second.activeGoalId()).isNotEqualTo(first.activeGoalId());
        assertThat(second.activeGoal()).isEqualTo("继续检查技能页");
        assertThat(second.goalCount()).isEqualTo(2);
    }

    @Test
    void running_container_should_queue_new_goal_without_replacing_current_goal() {
        Thread thread = Thread.newThread("thr_wu_3", "H:/aaa");
        WorkUnitItem first = service.createOrAppend(
                new WorkUnitCreateRequest("team", "前端验收组", "检查设置页", null),
                thread,
                new Turn("turn_wu_3", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
        service.markGoalRunning(first.activeGoalId(), "team", "team_running");

        WorkUnitItem second = service.createOrAppend(
                new WorkUnitCreateRequest("team", "前端验收组", "追加运行详情检查", null),
                thread,
                new Turn("turn_wu_3b", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));

        assertThat(second.workUnitId()).isEqualTo(first.workUnitId());
        assertThat(second.status()).isEqualTo("running");
        assertThat(second.activeGoalId()).isEqualTo(first.activeGoalId());
        assertThat(second.activeGoal()).isEqualTo("检查设置页");
        assertThat(second.goalCount()).isEqualTo(2);
        List<WorkUnitGoal> goals = service.listGoals(first.workUnitId());
        assertThat(goals).extracting(WorkUnitGoal::status)
                .containsExactly("running", "pending");
    }

    @Test
    void remove_should_hide_completed_container_and_create_new_one_for_same_name() {
        Thread thread = Thread.newThread("thr_wu_4", "H:/aaa");
        WorkUnitItem first = service.createOrAppend(
                new WorkUnitCreateRequest("orchestration", "登录页重构", "目标一", null),
                thread,
                new Turn("turn_wu_4", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
        service.markGoalCompleted(first.activeGoalId(), "目标一完成");

        service.remove(first.workUnitId());

        assertThat(service.listVisible(thread.id())).isEmpty();
        WorkUnitItem second = service.createOrAppend(
                new WorkUnitCreateRequest("orchestration", "登录页重构", "目标二", null),
                thread,
                new Turn("turn_wu_4b", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
        assertThat(second.workUnitId()).isNotEqualTo(first.workUnitId());
    }

    @Test
    void remove_should_reject_running_container() {
        Thread thread = Thread.newThread("thr_wu_5", "H:/aaa");
        WorkUnitItem item = service.createOrAppend(
                new WorkUnitCreateRequest("team", "运行中团队", "目标", null),
                thread,
                new Turn("turn_wu_5", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
        service.markGoalRunning(item.activeGoalId(), "team", "team_running_remove");

        assertThatThrownBy(() -> service.remove(item.workUnitId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("运行中");
    }

    @Test
    void mark_goal_failed_should_update_goal_and_container_status() {
        Thread thread = Thread.newThread("thr_wu_6", "H:/aaa");
        WorkUnitItem item = service.createOrAppend(
                new WorkUnitCreateRequest("orchestration", "failed container", "failed goal", null),
                thread,
                new Turn("turn_wu_6", thread.id()),
                thread.cwd(),
                AgentRunPolicy.of(SandboxMode.WORKSPACE_WRITE, ApprovalPolicy.ON_REQUEST));
        service.markGoalRunning(item.activeGoalId(), "orchestration", "orch_failed");

        service.markGoalFailed(item.activeGoalId(), "boom");

        WorkUnit workUnit = service.listVisible(thread.id()).getFirst();
        WorkUnitGoal goal = service.listGoals(item.workUnitId()).getFirst();
        assertThat(workUnit.status()).isEqualTo("failed");
        assertThat(goal.status()).isEqualTo("failed");
        assertThat(goal.errorMessage()).isEqualTo("boom");
        assertThat(goal.completedAt()).isNotNull();
    }
}
