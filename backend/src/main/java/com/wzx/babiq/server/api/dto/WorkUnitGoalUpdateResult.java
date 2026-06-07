package com.wzx.babiq.server.api.dto;

/**
 * workunit/goal/update 返回值。
 *
 * @param updatedGoal 已保存的目标快照
 * @param workUnit 刷新后的工作容器详情
 */
public record WorkUnitGoalUpdateResult(
        WorkUnitGoalInfo updatedGoal,
        WorkUnitInfo workUnit
) {
}
