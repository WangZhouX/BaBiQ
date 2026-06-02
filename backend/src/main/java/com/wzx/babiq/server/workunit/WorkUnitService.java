package com.wzx.babiq.server.workunit;

import com.wzx.babiq.server.agent.AgentRunPolicy;
import com.wzx.babiq.server.conversation.Thread;
import com.wzx.babiq.server.conversation.Turn;
import com.wzx.babiq.server.conversation.items.WorkUnitItem;

import java.util.List;

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
     * 标记目标完成，并把容器恢复到可复用的完成状态。
     */
    void markGoalCompleted(String goalId, String summary);

    void markGoalFailed(String goalId, String errorMessage);

    /**
     * 从 UI 移除容器。该操作只做软删除，不删除审计事实。
     */
    WorkUnit remove(String workUnitId);
}
