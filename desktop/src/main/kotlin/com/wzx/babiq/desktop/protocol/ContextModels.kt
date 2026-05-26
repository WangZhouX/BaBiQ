package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * context/status 的桌面端响应模型。
 *
 * 这个对象是 thread 级上下文窗口摘要：聊天输入栏只需要知道最近一次模型输入用了多少 token，
 * 不需要把完整快照 JSON 拉到主界面里，避免上下文审计数据干扰普通聊天流。
 *
 * @property threadId 当前会话 id，Controller 用它防止异步刷新结果串到别的会话。
 * @property windowOrdinal 当前短期窗口序号；P3-2 先固定为 0，后续压缩后会递增。
 * @property modelContextWindow 后端按 Provider/模型解析出的上下文窗口大小。
 * @property autoCompactThreshold 自动压缩阈值；P3-2 只展示，不真正触发压缩。
 * @property lastSnapshotId 最近一次上下文快照 id；为空表示该会话还没有模型调用快照。
 * @property lastEstimatedTokens 最近快照的预估 token 数。
 * @property lastActualPromptTokens 模型返回的真实 prompt token；供应商未返回时为空。
 * @property usageRatio 最近快照占模型窗口的比例，UI 用它选择提示色。
 * @property status 后端状态标签，例如 empty、ok、over_threshold。
 * @property activeSummaryId 当前窗口正在引用的短期摘要 id；为空表示尚未压缩。
 * @property compactionCount 当前会话已记录的压缩尝试次数。
 * @property lastCompactionStatus 最近一次压缩状态，例如 SUCCESS、SKIPPED、FAILED。
 */
@Serializable
data class ContextStatusResult(
	val threadId: String,
	val windowOrdinal: Int = 0,
	val modelContextWindow: Int = 0,
	val autoCompactThreshold: Int = 0,
	val lastSnapshotId: String? = null,
	val lastEstimatedTokens: Int = 0,
	val lastActualPromptTokens: Long? = null,
	val usageRatio: Double = 0.0,
	val status: String = "empty",
	val activeSummaryId: String? = null,
	val compactionCount: Long = 0,
	val lastCompactionStatus: String? = null,
)

/**
 * context/snapshot/get 与 run/turn/get 中复用的上下文快照详情。
 *
 * 快照是“本轮实际喂给模型的临时上下文窗口”的审计记录；它不会反写到聊天历史，
 * 只在运行详情里帮助用户确认当前轮次到底参考了哪些历史、工具和能力信息。
 *
 * @property snapshotId 快照 id。
 * @property threadId 所属会话 id。
 * @property turnId 所属 turn id。
 * @property phase 快照阶段，P3-2 固定为 pre_model_call。
 * @property providerId 本轮 Provider id；历史数据缺失时为空。
 * @property model 本轮模型名；历史数据缺失时为空。
 * @property cwd 本轮工作目录快照；用于排查跨工作区上下文污染。
 * @property windowOrdinal 所属短期窗口序号。
 * @property modelContextWindow 模型上下文窗口大小。
 * @property autoCompactThreshold 自动压缩阈值。
 * @property estimatedTokens 快照预估 token。
 * @property actualPromptTokens 模型真实 prompt token；未返回 usage 时为空。
 * @property includedItemCount 纳入模型输入的上下文片段数。
 * @property excludedItemCount 被裁剪或仅保留审计的片段数。
 * @property usageRatio token 使用率。
 * @property inputPreview 本轮输入预览。
 * @property createdAt 快照创建时间。
 * @property items 快照条目列表，运行详情按需展示统计，不在聊天主区展开。
 */
@Serializable
data class ContextSnapshotInfo(
	val snapshotId: String,
	val threadId: String,
	val turnId: String,
	val phase: String,
	val providerId: String? = null,
	val model: String? = null,
	val cwd: String? = null,
	val windowOrdinal: Int = 0,
	val modelContextWindow: Int = 0,
	val autoCompactThreshold: Int = 0,
	val estimatedTokens: Int = 0,
	val actualPromptTokens: Long? = null,
	val includedItemCount: Int = 0,
	val excludedItemCount: Int = 0,
	val usageRatio: Double = 0.0,
	val inputPreview: String? = null,
	val createdAt: String,
	val items: List<ContextSnapshotItemInfo> = emptyList(),
)

/**
 * 上下文快照中的单个片段。
 *
 * @property sourceId 来源 id，例如历史 item id、memory id 或工具能力名。
 * @property sourceType 来源类型，保持字符串以兼容后端未来扩展。
 * @property priority 分层优先级，帮助用户理解“本轮输入为什么优先保留它”。
 * @property included true 表示该片段实际进入模型输入。
 * @property reason 纳入或排除原因。
 * @property tokenEstimate 该片段的 token 预估。
 */
@Serializable
data class ContextSnapshotItemInfo(
	val sourceId: String,
	val sourceType: String,
	val priority: String,
	val included: Boolean,
	val reason: String,
	val tokenEstimate: Int = 0,
)
