package com.wzx.babiq.server.workunit;

import java.util.List;
import java.util.Optional;

/**
 * WorkUnit 事实源端口。
 *
 * <p>Agent、工具和 JSON-RPC 层只依赖该端口；SQLite/MyBatis-Plus 细节放在 persistence adapter 中。</p>
 */
public interface WorkUnitRepository {

    Optional<WorkUnit> findVisibleByName(String threadId, String kind, String normalizedName);

    Optional<WorkUnit> findById(String workUnitId);

    Optional<WorkUnitGoal> findGoalById(String goalId);

    WorkUnit save(WorkUnit workUnit);

    WorkUnitGoal saveGoal(WorkUnitGoal goal);

    List<WorkUnit> listVisible(String threadId);

    List<WorkUnitGoal> listGoals(String workUnitId);
}
