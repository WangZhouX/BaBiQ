package com.wzx.babiq.server.workunit;

import java.time.Instant;

/**
 * 工作容器配置快照。
 *
 * <p>它保存右侧 Inspector 中的编排节点或团队成员设置，例如节点任务、成员任务和模型选择。
 * 配置是 WorkUnit 的辅助事实源，不替代目标队列，也不表示已经开始执行。</p>
 *
 * @param workUnitId 所属工作容器 id
 * @param configJson 配置 JSON，桌面端按 kind 解析为编排节点或团队成员
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record WorkUnitConfig(
        String workUnitId,
        String configJson,
        String structureJson,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkUnitConfig(String workUnitId, String configJson, Instant createdAt, Instant updatedAt) {
        this(workUnitId, configJson, null, createdAt, updatedAt);
    }
}
