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

/**
 * team/list 返回的团队运行摘要。
 */
@Serializable
data class TeamInfo(
	val teamId: String,
	val threadId: String,
	val turnId: String,
	val title: String,
	val goal: String? = null,
	val status: String,
	val cwd: String? = null,
	val sandboxMode: String? = null,
	val approved: Boolean = false,
	val frozen: Boolean = false,
	val maxRounds: Int = 0,
	val currentRound: Int = 0,
	val currentAgent: String? = null,
	val summary: String? = null,
	val errorMessage: String? = null,
	val memberCount: Int = 0,
) {
	fun toThreadItem(members: List<ThreadItem.TeamMember> = emptyList()): ThreadItem.Team =
		ThreadItem.Team(
			id = "it_$teamId",
			teamId = teamId,
			title = title,
			status = status,
			summary = summary ?: errorMessage,
			approved = approved,
			frozen = frozen,
			currentAgent = currentAgent,
			round = currentRound.takeIf { it > 0 },
			maxRounds = maxRounds.takeIf { it > 0 },
			members = members,
		)
}

/**
 * team/get 返回的成员聚合状态。
 */
@Serializable
data class TeamMemberInfo(
	val teamId: String,
	val memberId: String,
	val name: String,
	val displayName: String? = null,
	val role: String? = null,
	val mode: String,
	val toolNames: String? = null,
	val status: String,
	val memberOrder: Int = 0,
	val toolCallCount: Int = 0,
	val tokenEstimate: Int = 0,
	val summary: String? = null,
) {
	fun toThreadMember(): ThreadItem.TeamMember =
		ThreadItem.TeamMember(
			memberId = memberId,
			name = name,
			displayName = displayName,
			status = status,
			mode = mode,
			task = role,
			toolCallCount = toolCallCount.takeIf { it > 0 },
			tokenEstimate = tokenEstimate.takeIf { it > 0 },
			summary = summary,
		)
}

/**
 * team/get 返回的团队时间线消息。
 */
@Serializable
data class TeamMessageInfo(
	val teamId: String,
	val messageId: String,
	val threadId: String? = null,
	val turnId: String? = null,
	val fromAgent: String,
	val toAgent: String,
	val messageType: String,
	val content: String,
	val routeDecisionJson: String? = null,
	val round: Int = 0,
) {
	fun toThreadItem(): ThreadItem.TeamMessage =
		ThreadItem.TeamMessage(
			id = "it_team_msg_$messageId",
			messageId = messageId,
			teamId = teamId,
			fromAgent = fromAgent,
			toAgent = toAgent,
			messageType = messageType,
			content = content,
			round = round.takeIf { it > 0 },
		)
}

/**
 * team/list 返回值。
 */
@Serializable
data class TeamListResult(
	val teams: List<TeamInfo> = emptyList(),
)

/**
 * team/get 返回值。
 */
@Serializable
data class TeamGetResult(
	val team: TeamInfo,
	val members: List<TeamMemberInfo> = emptyList(),
	val messages: List<TeamMessageInfo> = emptyList(),
) {
	fun toThreadTeam(): ThreadItem.Team =
		team.toThreadItem(members.map { it.toThreadMember() })

	fun toThreadMessages(): List<ThreadItem.TeamMessage> =
		messages.map { it.toThreadItem() }
}
