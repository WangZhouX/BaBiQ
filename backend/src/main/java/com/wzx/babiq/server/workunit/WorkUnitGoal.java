package com.wzx.babiq.server.workunit;

import java.time.Instant;

/**
 * 工作容器内的一批目标。
 *
 * <p>同一个 WorkUnit 可以连续追加多个目标。运行中追加的新目标默认保持 pending，避免抢占当前执行；
 * 显式启动某个目标时才会写入 runRefType/runRefId。</p>
 *
 * @param goalId 协议层目标 id
 * @param workUnitId 所属工作容器 id
 * @param threadId 所属对话线程 id
 * @param goalText 目标正文
 * @param status pending、running、completed、failed 或 cancelled
 * @param runRefType team 或 orchestration
 * @param runRefId 关联的 team_ 或 orch_ 运行 id
 * @param summary 完成摘要
 * @param errorMessage 失败原因
 * @param createdAt 创建时间
 * @param startedAt 启动时间
 * @param completedAt 完成时间
 */
public record WorkUnitGoal(
        String goalId,
        String workUnitId,
        String threadId,
        String goalText,
        String status,
        String runRefType,
        String runRefId,
        String summary,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
}
