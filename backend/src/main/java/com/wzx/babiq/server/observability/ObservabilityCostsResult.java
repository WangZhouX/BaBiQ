package com.wzx.babiq.server.observability;

import java.util.List;

/**
 * `observability/costs` 的协议响应。
 *
 * @param range 本次统计窗口。
 * @param models Provider/Model 维度成本聚合。
 */
public record ObservabilityCostsResult(String range, List<ModelCostStats> models) {
}
