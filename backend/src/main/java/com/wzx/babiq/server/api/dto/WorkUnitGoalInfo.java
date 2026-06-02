package com.wzx.babiq.server.api.dto;

/**
 * 工作容器目标队列中的一个目标。
 *
 * @param goalId 目标 id
 * @param workUnitId 所属工作容器 id
 * @param goalText 目标正文
 * @param status 目标状态
 * @param runRefType 关联运行类型
 * @param runRefId 关联运行 id
 * @param summary 完成摘要
 * @param errorMessage 失败原因
 * @param createdAt 创建时间
 * @param startedAt 启动时间
 * @param completedAt 完成时间
 */
public record WorkUnitGoalInfo(
        String goalId,
        String workUnitId,
        String goalText,
        String status,
        String runRefType,
        String runRefId,
        String summary,
        String errorMessage,
        String createdAt,
        String startedAt,
        String completedAt
) {
}
