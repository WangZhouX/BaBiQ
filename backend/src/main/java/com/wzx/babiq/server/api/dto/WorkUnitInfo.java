package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * 桌面端可展示的工作容器摘要。
 *
 * @param workUnitId 工作容器 id
 * @param threadId 所属对话 id
 * @param kind orchestration 或 team
 * @param name 用户命名
 * @param status 容器状态
 * @param currentGoalId 当前目标 id
 * @param cwd 创建时工作目录
 * @param sandboxMode 创建时沙箱权限
 * @param removed 是否已从 UI 移除
 * @param updatedAt 更新时间
 * @param configJson 右侧 Inspector 的配置 JSON
 * @param goals 目标队列
 */
public record WorkUnitInfo(
        String workUnitId,
        String threadId,
        String kind,
        String name,
        String status,
        String currentGoalId,
        String cwd,
        String sandboxMode,
        boolean removed,
        String updatedAt,
        String configJson,
        List<WorkUnitGoalInfo> goals
) {
}
