package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 后端发起工具审批时推给桌面端的 payload。
 *
 * @property threadId 审批所属会话。
 * @property turnId 审批所属轮次。
 * @property itemId 审批 item id，主要用于 UI 展示和后续扩展定位。
 * @property toolName 触发审批的工具名。
 * @property arguments 工具参数 JSON 字符串，P1 先直接展示给用户确认。
 * @property description 后端生成的风险说明或执行说明。
 */
@Serializable
data class ApprovalRequestPayload(
	val threadId: String,
	val turnId: String,
	val itemId: String,
	val toolName: String,
	val arguments: String,
	val description: String,
)

/**
 * 桌面端提交审批结果时发送给后端的参数。
 *
 * @property threadId 审批所属会话，后端用它找到待恢复的 HITL 暂停点。
 * @property turnId 审批所属轮次，后端用它更新 turn 状态。
 * @property decision `approve`、`deny`、`edit` 或 `always`。
 * @property editedArgs 只有 decision 为 `edit` 时才携带修改后的工具参数。
 * @property scope `always` 的作用域；P2-3 只发送 session，避免跨会话永久放行。
 */
@Serializable
data class ApprovalRespondParams(
	val threadId: String,
	val turnId: String,
	val decision: String,
	val editedArgs: String? = null,
	val scope: String? = null,
)
