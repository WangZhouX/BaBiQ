package com.wzx.babiq.desktop.protocol

import kotlinx.serialization.Serializable

/**
 * 发送团队直发消息的 JSON-RPC 参数。
 *
 * @property teamId 当前团队运行 id，由右侧 TeamUiState.current 提供。
 * @property toAgent 目标成员名，必须来自当前团队成员列表，避免 UI 发给不存在的 Agent。
 * @property content 用户写给队友的补充信息；后端会落库为 teamMessage。
 */
@Serializable
data class TeamMessageSendParams(
	val teamId: String,
	val toAgent: String,
	val content: String,
)

/**
 * `team/message/send` 返回值。
 *
 * @property item 后端已经持久化的 teamMessage item，Controller 会直接交给 reducer 合并到团队时间线。
 */
@Serializable
data class TeamMessageSendResult(
	val item: ThreadItem.TeamMessage,
)
