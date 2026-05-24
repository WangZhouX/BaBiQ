package com.wzx.babiq.server.observability;

import java.util.List;

/**
 * `observability/costs` 的协议响应。
 *
 * <p>方法名保留旧协议兼容性；返回内容已经改为 Provider/Model token 用量。</p>
 *
 * @param range 本次统计窗口。
 * @param models Provider/Model 维度 token 用量聚合。
 */
public record ObservabilityCostsResult(String range, List<ModelUsageStats> models) {
}
