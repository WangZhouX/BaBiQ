package com.wzx.babiq.server.observability;

import java.util.List;

/**
 * `observability/tools` 的协议响应。
 *
 * @param range 本次统计窗口。
 * @param tools 工具调用聚合列表。
 */
public record ObservabilityToolsResult(String range, List<ToolStats> tools) {
}
