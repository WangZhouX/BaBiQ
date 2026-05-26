package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * memory/status 的桌面端响应模型。
 *
 * @property enabled 长期记忆总开关，关闭时后台不抽取也不注入。
 * @property generateEnabled 生成开关，关闭时保留读取但暂停 Phase1/Phase2。
 * @property readEnabled 读取开关，关闭时不把 memory_summary 注入上下文窗口。
 * @property rootDir Markdown 镜像目录，仅用于设置页展示。
 * @property pendingJobs 待执行记忆任务数量。
 * @property runningJobs 正在执行记忆任务数量。
 * @property cleanCandidateCount 尚未归并的 CLEAN 候选数量。
 * @property lastSummaryArtifactId 最近一次 memory_summary 产物 id。
 * @property lastConsolidatedAt 最近一次 Phase2 归并完成时间。
 * @property phase2Generation 最近 Phase2 generation。
 */
@Serializable
data class MemoryStatusResult(
	val enabled: Boolean,
	val generateEnabled: Boolean,
	val readEnabled: Boolean,
	val rootDir: String,
	val pendingJobs: Long = 0,
	val runningJobs: Long = 0,
	val cleanCandidateCount: Long = 0,
	val lastSummaryArtifactId: String? = null,
	val lastConsolidatedAt: String? = null,
	val phase2Generation: Int = 0,
)

/**
 * memory/settings/set 的局部更新参数。
 *
 * 空字段表示保留后端当前配置，避免 UI 每次都提交完整设置快照。
 */
@Serializable
data class MemorySettingsSetParams(
	val enabled: Boolean? = null,
	val generateEnabled: Boolean? = null,
	val readEnabled: Boolean? = null,
)

/** memory/settings/set 更新后的开关状态。 */
@Serializable
data class MemorySettingsSetResult(
	val enabled: Boolean,
	val generateEnabled: Boolean,
	val readEnabled: Boolean,
)

/** 记忆任务列表中的单条审计信息。 */
@Serializable
data class MemoryJobInfo(
	val jobId: String,
	val jobType: String,
	val jobKey: String,
	val generation: Int = 0,
	val status: String,
	val createdAt: String,
)

/** memory/jobs/list 响应。 */
@Serializable
data class MemoryJobsListResult(
	val jobs: List<MemoryJobInfo> = emptyList(),
)

/** 长期记忆产物列表中的单条审计信息。 */
@Serializable
data class MemoryArtifactInfo(
	val artifactId: String,
	val artifactType: String,
	val artifactPath: String,
	val version: Int,
	val tokenEstimate: Int = 0,
	val createdAt: String,
)

/** memory/artifacts/list 响应。 */
@Serializable
data class MemoryArtifactsListResult(
	val artifacts: List<MemoryArtifactInfo> = emptyList(),
)

/** memory/consolidate 响应。 */
@Serializable
data class MemoryConsolidateResult(
	val queued: Boolean,
	val jobId: String? = null,
	val generation: Int = 0,
	val status: String,
)
