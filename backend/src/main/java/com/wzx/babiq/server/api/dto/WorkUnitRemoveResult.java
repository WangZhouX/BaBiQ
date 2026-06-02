package com.wzx.babiq.server.api.dto;

/**
 * workunit/remove 返回值。
 *
 * @param workUnitId 被移除的工作容器 id
 * @param kind 工作容器类型
 * @param name 用户命名
 * @param status 移除后的状态
 * @param removed 是否已软移除
 */
public record WorkUnitRemoveResult(
        String workUnitId,
        String kind,
        String name,
        String status,
        boolean removed
) {
}
