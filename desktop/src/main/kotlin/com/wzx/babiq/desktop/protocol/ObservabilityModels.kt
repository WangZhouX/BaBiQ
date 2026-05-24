package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * observability/snapshot 的桌面端响应模型。
 *
 * 这个 DTO 对应后端 LocalObservabilitySnapshot。它是“运行详情面板的本地统计总览”，
 * 只承载展示所需的聚合结果，不暴露 SQLite 表结构和 SQL 细节。
 *
 * @property range 本次统计窗口，合法值为 7d、30d 或 all。
 * @property totals 总量统计，例如 turn 数、token 数和估算成本。
 * @property byProvider Provider 维度聚合；model 为空表示按 Provider 汇总。
 * @property byModel Provider/Model 维度聚合，适合展示模型成本排行。
 * @property byTool 工具调用维度聚合，适合展示工具次数和失败次数。
 * @property byStatus turn 状态分布，例如 COMPLETED、FAILED。
 */
@Serializable
data class ObservabilitySnapshotResult(
	val range: String = "7d",
	val totals: ObservabilityTotalsInfo = ObservabilityTotalsInfo(),
	val byProvider: List<ModelCostStatsInfo> = emptyList(),
	val byModel: List<ModelCostStatsInfo> = emptyList(),
	val byTool: List<ToolStatsInfo> = emptyList(),
	val byStatus: List<StatusStatsInfo> = emptyList(),
)

/**
 * 本地统计总量。
 *
 * @property turns 统计窗口内的 turn 总数。
 * @property failedTurns 统计窗口内失败 turn 数。
 * @property promptTokens 输入 token 总数。
 * @property completionTokens 输出 token 总数。
 * @property estimatedCostUsd 估算美元成本；桌面端只展示，不做二次计费计算。
 */
@Serializable
data class ObservabilityTotalsInfo(
	val turns: Long = 0,
	val failedTurns: Long = 0,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val estimatedCostUsd: Double = 0.0,
)

/**
 * Provider 或 Provider/Model 维度的成本统计。
 *
 * @property providerId Provider 稳定标识，来自 turn 启动时的快照。
 * @property model 模型名；按 Provider 聚合时为空。
 * @property turns 该维度下的 turn 数。
 * @property failedTurns 该维度下的失败 turn 数。
 * @property promptTokens 输入 token 总数。
 * @property completionTokens 输出 token 总数。
 * @property estimatedCostUsd 该维度的估算美元成本。
 */
@Serializable
data class ModelCostStatsInfo(
	val providerId: String? = null,
	val model: String? = null,
	val turns: Long = 0,
	val failedTurns: Long = 0,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val estimatedCostUsd: Double = 0.0,
)

/**
 * 工具调用维度的统计。
 *
 * @property toolName 工具名，例如 read_file 或 exec_shell。
 * @property calls 调用次数。
 * @property failures 失败或拒绝次数。
 * @property avgDurationMs 已完成工具调用的平均耗时，单位毫秒。
 */
@Serializable
data class ToolStatsInfo(
	val toolName: String,
	val calls: Long = 0,
	val failures: Long = 0,
	val avgDurationMs: Long = 0,
)

/**
 * turn 状态分布统计。
 *
 * @property status 后端 turn 状态，例如 COMPLETED、FAILED、INTERRUPTED。
 * @property turns 该状态下的 turn 数。
 */
@Serializable
data class StatusStatsInfo(
	val status: String,
	val turns: Long = 0,
)

/**
 * observability/tools 的桌面端响应模型。
 *
 * @property range 本次统计窗口。
 * @property tools 工具维度统计列表。
 */
@Serializable
data class ObservabilityToolsResult(
	val range: String = "7d",
	val tools: List<ToolStatsInfo> = emptyList(),
)

/**
 * observability/costs 的桌面端响应模型。
 *
 * @property range 本次统计窗口。
 * @property models Provider/Model 维度成本聚合。
 */
@Serializable
data class ObservabilityCostsResult(
	val range: String = "7d",
	val models: List<ModelCostStatsInfo> = emptyList(),
)
