package com.wzx.babiq.server.api.dto;

import java.util.List;

/**
 * workunit/list 返回值。
 *
 * @param workUnits 当前对话下未被移除的工作容器
 */
public record WorkUnitListResult(List<WorkUnitInfo> workUnits) {
}
