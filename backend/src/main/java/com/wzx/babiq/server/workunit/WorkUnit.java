package com.wzx.babiq.server.workunit;

import java.time.Instant;

/**
 * 命名工作容器。
 *
 * <p>WorkUnit 统一承载 P6-4 的编排容器和团队容器。它是“可复用的工作单元”，不是一次 turn；
 * 一个容器可以连续承接多个 {@link WorkUnitGoal}，但只有用户显式启动后才会进入真实执行链路。</p>
 *
 * @param workUnitId 协议层工作容器 id
 * @param threadId 所属对话线程
 * @param kind orchestration 或 team
 * @param name 用户可读名称
 * @param normalizedName 归一化名称，用于同名复用
 * @param status waiting_config、waiting_start、running、completed、failed 或 removed
 * @param currentGoalId 当前正在执行或最近激活的目标 id
 * @param cwd 创建时工作目录快照
 * @param sandboxMode 创建时沙箱模式快照
 * @param removed 是否已从 UI 移除
 * @param removedAt 移除时间
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record WorkUnit(
        String workUnitId,
        String threadId,
        String kind,
        String name,
        String normalizedName,
        String status,
        String currentGoalId,
        String cwd,
        String sandboxMode,
        boolean removed,
        Instant removedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
