package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * run/turns/list 的桌面端响应模型。
 *
 * 后端按 thread 维度返回历史 turn 摘要，桌面端运行详情面板用它展示“当前会话曾经跑过什么”。
 *
 * @property turns 按 startedAt 倒序排列的 turn 摘要列表。
 * @property nextCursor 下一页游标；P2-4 后端暂时返回 null，保留给后续分页。
 */
@Serializable
data class RunTurnListResult(
	val turns: List<RunTurnSummaryInfo> = emptyList(),
	val nextCursor: String? = null,
)

/**
 * 单个 turn 的历史运行摘要。
 *
 * 这个模型只放运行列表需要的字段，详细 item、审批和工具调用由 run/turn/get 再按需读取。
 *
 * @property turnId 后端 turn id，点击历史项或默认选中时用它查询详情。
 * @property threadId turn 所属 thread id，用于避免跨会话运行记录混淆。
 * @property status turn 当前或最终状态，例如 COMPLETED、FAILED、INTERRUPTED、EXPIRED。
 * @property inputText 用户原始输入摘要，运行详情列表用它帮助识别本轮任务。
 * @property cwd 本轮执行时的工作目录快照；即使 UI 后来切换目录，历史记录也保持原值。
 * @property providerId 本轮使用的 Provider 快照；为空表示历史数据没有记录。
 * @property model 本轮使用的模型快照；为空表示历史数据没有记录。
 * @property startedAt 后端 ISO 时间字符串，列表直接裁剪展示。
 * @property completedAt 完成时间；未完成或被恢复收束前可能为空。
 * @property recoveryReason 后端启动恢复时写入的收束原因；非恢复记录为空。
 * @property recoveredAt 恢复收束时间；非恢复记录为空。
 */
@Serializable
data class RunTurnSummaryInfo(
	val turnId: String,
	val threadId: String,
	val status: String,
	val inputText: String,
	val cwd: String,
	val providerId: String? = null,
	val model: String? = null,
	val startedAt: String,
	val completedAt: String? = null,
	val recoveryReason: String? = null,
	val recoveredAt: String? = null,
)

/**
 * run/turn/get 响应里的审批记录。
 *
 * @property approvalId 审批业务 id，来自后端 approval item。
 * @property toolName 触发审批的工具名。
 * @property argsJson 原始工具参数 JSON。
 * @property editedArgsJson 用户编辑后的参数 JSON；未编辑时为空。
 * @property decision 用户最终决策，例如 approve、deny、always；尚未处理时为空。
 * @property scope always 决策的作用域；普通 approve/deny 为空。
 * @property status 审批状态，例如 pending、resolved、expired。
 * @property createdAt 审批创建时间。
 * @property resolvedAt 审批完成或过期时间；仍 pending 时为空。
 */
@Serializable
data class RunApprovalInfo(
	val approvalId: String,
	val toolName: String,
	val argsJson: String,
	val editedArgsJson: String? = null,
	val decision: String? = null,
	val scope: String? = null,
	val status: String,
	val createdAt: String,
	val resolvedAt: String? = null,
)

/**
 * run/turn/get 响应里的工具调用记录。
 *
 * 工具详情仍以 item payload 为准；这里保存的是可统计、可检索的轻量索引。
 *
 * @property toolCallId 工具调用业务 id。
 * @property toolName 工具名，例如 exec_shell、read_file。
 * @property argsJson 工具参数 JSON，用于排查本轮到底请求了什么。
 * @property status 工具状态，例如 running、completed、failed、denied。
 * @property resultPreview 结果短预览，避免把超长输出塞进详情面板。
 * @property errorMessage 失败、拒绝或异常原因；成功时为空。
 * @property startedAt 工具开始时间。
 * @property completedAt 工具结束时间；仍 running 时为空。
 */
@Serializable
data class RunToolCallInfo(
	val toolCallId: String,
	val toolName: String,
	val argsJson: String,
	val status: String,
	val resultPreview: String? = null,
	val errorMessage: String? = null,
	val startedAt: String,
	val completedAt: String? = null,
)

/**
 * run/turn/get 的桌面端响应模型。
 *
 * items 和 summary 复用 ThreadItem，这样历史详情和实时聊天使用同一套协议解析逻辑。
 *
 * @property turn 当前 turn 的摘要快照。
 * @property items 本轮持久化的协议 item。
 * @property summary 后端合成或持久化的 turnSummary；没有摘要时为空。
 * @property approvals 本轮审批记录。
 * @property toolCalls 本轮工具调用记录。
 * @property contextSnapshot 本轮模型调用前的上下文窗口快照；没有生成快照或旧数据为空时为 null。
 */
@Serializable
data class RunTurnDetailResult(
	val turn: RunTurnSummaryInfo,
	val items: List<ThreadItem> = emptyList(),
	val summary: ThreadItem.TurnSummary? = null,
	val approvals: List<RunApprovalInfo> = emptyList(),
	val toolCalls: List<RunToolCallInfo> = emptyList(),
	val contextSnapshot: ContextSnapshotInfo? = null,
)

/**
 * run/recovery/status 的桌面端响应模型。
 *
 * 运行详情面板用它提示“最近一次后端启动是否收束过遗留任务”。
 *
 * @property lastRecoveredAt 最近一次启动恢复时间；后端从未执行恢复时为空。
 * @property interruptedTurns 被标记为 INTERRUPTED 的遗留 turn 数量。
 * @property expiredTurns 被标记为 EXPIRED 的遗留 turn 数量。
 * @property expiredApprovals 被标记为 expired 的 pending approval 数量。
 */
@Serializable
data class RunRecoveryStatusResult(
	val lastRecoveredAt: String? = null,
	val interruptedTurns: Int = 0,
	val expiredTurns: Int = 0,
	val expiredApprovals: Int = 0,
)
