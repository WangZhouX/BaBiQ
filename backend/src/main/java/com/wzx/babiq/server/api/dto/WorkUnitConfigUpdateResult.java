package com.wzx.babiq.server.api.dto;

/**
 * workunit/config/update 返回值。
 *
 * @param workUnit 保存配置后的工作容器详情
 */
public record WorkUnitConfigUpdateResult(
        WorkUnitInfo workUnit
) {
}
