package com.wzx.babiq.server.observability;

import java.util.List;

/**
 * 本地可观测快照响应。
 *
 * <p>该对象直接作为 JSON-RPC `observability/snapshot` 的 result 返回。
 * 它只暴露协议层稳定字段，不暴露 SQLite 表名、列名或 MyBatis 聚合细节。</p>
 *
 * @param range 统计窗口，合法值为 7d、30d 或 all。
 * @param totals 总量统计。
 * @param byProvider Provider 维度聚合。
 * @param byModel Provider/Model 维度聚合。
 * @param byTool 工具维度聚合。
 * @param byStatus turn 状态分布。
 */
public record LocalObservabilitySnapshot(
        String range,
        ObservabilityTotals totals,
        List<ModelCostStats> byProvider,
        List<ModelCostStats> byModel,
        List<ToolStats> byTool,
        List<StatusStats> byStatus) {
}
