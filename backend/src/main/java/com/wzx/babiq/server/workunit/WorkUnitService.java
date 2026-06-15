package com.wzx.babiq.server.workunit;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;

import java.util.List;
import java.util.Optional;

/**
 * 工作容器应用服务。
 *
 * <p>slash 命令、自然语言管理工具和详情页启动动作都通过该端口操作 WorkUnit，
 * 避免把编排/团队生命周期散落在 UI 或工具实现里。</p>
 */
public interface WorkUnitService {

    /**
     * 创建或复用工作容器，并追加一个目标。
     *
     * @param request 创建请求
     * @param thread 当前会话
     * @param turn 当前 turn
     * @param cwd 当前工作目录
     * @param runPolicy 本轮权限策略快照
     * @return 用于推送给桌面端的工作容器 item
     */
    WorkUnitItem createOrAppend(WorkUnitCreateRequest request,
                                Thread thread,
                                Turn turn,
                                String cwd,
                                AgentRunPolicy runPolicy);

    /**
     * 列出某个对话下未移除的工作容器。
     *
     * @param threadId 对话线程 id
     * @return 按更新时间倒序排列的可见容器
     */
    List<WorkUnit> listVisible(String threadId);

    /**
     * 列出某个容器下的目标队列。
     *
     * @param workUnitId 工作容器 id
     * @return 按创建时间升序排列的目标
     */
    List<WorkUnitGoal> listGoals(String workUnitId);

    void requireGoalKind(String goalId, String expectedKind);

    /**
     * 把持久化工作容器转换为协议 item，供工具和 JSON-RPC handler 推送给桌面端。
     *
     * @param workUnit 工作容器
     * @return 协议 item
     */
    WorkUnitItem itemFor(WorkUnit workUnit);

    /**
     * 标记目标已被显式启动，并关联真实 flow/team 运行记录。
     */
    void markGoalRunning(String goalId, String runRefType, String runRefId);

    /**
     * 修改一个尚未启动的目标文本。
     *
     * <p>这是工作容器详情页和自然语言管理工具共享的配置入口。只有 pending 目标允许修改；
     * 已经 running/completed/failed 的目标保留审计事实，不做原地改写。</p>
     */
    WorkUnitGoal updateGoal(String goalId, String goalText);

    /**
     * 修改工作容器的显示名称，并同步用于复用/去重的 normalizedName。
     */
    WorkUnit rename(String workUnitId, String name);

    default WorkUnitConfig updateConfig(String workUnitId, String configJson) {
        return updateConfig(workUnitId, configJson, null);
    }

    WorkUnitConfig updateConfig(String workUnitId, String configJson, String structureJson);

    Optional<WorkUnitConfig> findConfig(String workUnitId);

    /**
     * 为某个对话中的工作容器选择当前可启动目标。
     *
     * <p>桌面详情页点击“开始执行/重新执行”时，turn/start 会先调用该方法确定 goalId，
     * 再把 goalId 绑定到本轮 AgentLoop 的观测上下文中。失败态重试会复制一条新的 pending 目标，
     * 原 failed 目标保留为审计事实；这样后续 flow/team 工具调用可以确定性回写同一个工作容器。</p>
     */
    WorkUnitGoal selectPendingGoalForTurn(String threadId, String workUnitId);

    /**
     * 标记目标完成，并把容器恢复到可复用的完成状态。
     */
    void markGoalCompleted(String goalId, String summary);

    void markGoalFailed(String goalId, String errorMessage);

    int recoverAbandonedRunning();

    /**
     * 从 UI 移除容器。该操作只做软删除，不删除审计事实。
     */
    WorkUnit remove(String workUnitId);
}
