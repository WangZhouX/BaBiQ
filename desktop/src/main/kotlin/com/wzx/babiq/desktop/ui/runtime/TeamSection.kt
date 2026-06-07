package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.TeamUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

private const val TeamMessagePreviewMaxChars = 80

data class TeamSectionModel(
	val visible: Boolean,
	val title: String,
	val subtitle: String,
	val selectedAgent: String? = null,
	val memberNames: List<String> = emptyList(),
	val members: List<TeamMemberRow> = emptyList(),
	val messages: List<TeamMessageRow> = emptyList(),
	val directError: String? = null,
	val sendingDirect: Boolean = false,
	val config: WorkUnitDetailModel? = null,
)

data class TeamMemberRow(
	val title: String,
	val meta: String,
	val task: String? = null,
	val summary: String? = null,
)

data class TeamMessageRow(
	val meta: String,
	val preview: String,
)

fun buildTeamSectionModel(
	state: TeamUiState,
	modelLabel: String = "未选择模型",
): TeamSectionModel {
	val team = state.current
	if (team != null) {
		val selectedAgent = state.selectedAgent ?: team.currentAgent ?: team.members.firstOrNull()?.name
		return TeamSectionModel(
			visible = true,
			title = "团队协作 · ${team.title}",
			subtitle = buildSubtitle(team),
			selectedAgent = selectedAgent,
			memberNames = team.members.map { it.name },
			members = team.members.map { it.toRow() },
			messages = state.messages.takeLast(6).map { it.toRow() },
			directError = state.directError,
			sendingDirect = state.sendingDirect,
			config = null,
		)
	}
	val config = state.configuringWorkUnit ?: return TeamSectionModel(false, "", "")
	return TeamSectionModel(
		visible = true,
		title = "团队详情 · ${config.name}",
		subtitle = "${statusLabel(config.status)} / ${config.goals.size} 个目标 / 等待手动启动",
		config = workUnitDetailModel(config, modelLabel),
	)
}

@Composable
fun TeamSection(
	state: TeamUiState,
	modelLabel: String = "未选择模型",
	onStartWorkUnit: (String) -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onSendTeamMessage: (String, String) -> Unit = { _, _ -> },
) {
	val model = buildTeamSectionModel(state, modelLabel)
	if (!model.visible) {
		return
	}

	var selectedAgent by remember(model.memberNames, model.selectedAgent) {
		mutableStateOf(model.selectedAgent ?: model.memberNames.firstOrNull().orEmpty())
	}
	var draft by remember { mutableStateOf("") }
	LaunchedEffect(model.memberNames, model.selectedAgent) {
		if (selectedAgent !in model.memberNames) {
			selectedAgent = model.selectedAgent ?: model.memberNames.firstOrNull().orEmpty()
		}
	}

	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
			Text(model.subtitle, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
			model.config?.let { config ->
				WorkUnitConfigCard(config, onStart = onStartWorkUnit, onUpdateGoal = onUpdateWorkUnitGoal)
			}
			model.members.forEach { TeamMemberRowView(it) }
			if (model.messages.isNotEmpty()) {
				Text("团队消息", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
				model.messages.forEach { TeamMessageRowView(it) }
			}
			if (model.memberNames.isNotEmpty()) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
					model.memberNames.forEach { name ->
						FilterChip(
							selected = name == selectedAgent,
							onClick = { selectedAgent = name },
							label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
						)
					}
				}
				OutlinedTextField(
					value = draft,
					onValueChange = { draft = it },
					modifier = Modifier.fillMaxWidth(),
					minLines = 2,
					maxLines = 4,
					label = { Text("给队友补充消息") },
				)
				Button(
					onClick = {
						val content = draft.trim()
						if (selectedAgent.isNotBlank() && content.isNotEmpty()) {
							onSendTeamMessage(selectedAgent, content)
							draft = ""
						}
					},
					enabled = !model.sendingDirect && selectedAgent.isNotBlank() && draft.isNotBlank(),
				) {
					Text(if (model.sendingDirect) "发送中" else "发送给 $selectedAgent")
				}
			}
			model.directError?.let { error ->
				Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
			}
		}
	}
}

@Composable
private fun TeamMemberRowView(row: TeamMemberRow) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Text(row.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
		Text(row.meta, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		row.task?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
		row.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
	}
}

@Composable
private fun TeamMessageRowView(row: TeamMessageRow) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Panel, RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Text(row.meta, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(row.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
	}
}

private fun buildSubtitle(team: ThreadItem.Team): String =
	listOfNotNull(
		statusLabel(team.status),
		team.round?.let { round -> team.maxRounds?.let { max -> "第 $round/$max 轮" } ?: "第 $round 轮" },
		team.currentAgent?.let { "当前 $it" },
		if (team.approved == true && team.frozen == true) "已审批并冻结" else null,
	).joinToString(" / ")

private fun ThreadItem.TeamMember.toRow(): TeamMemberRow =
	TeamMemberRow(
		title = displayName ?: name,
		meta = listOfNotNull(
			statusLabel(status),
			modeLabel(mode),
			toolCallCount?.let { "$it 工具" },
			tokenEstimate?.let { "$it token" },
		).joinToString(" · "),
		task = task,
		summary = summary,
	)

private fun ThreadItem.TeamMessage.toRow(): TeamMessageRow =
	TeamMessageRow(
		meta = listOfNotNull(
			"$fromAgent -> $toAgent",
			messageTypeLabel(messageType),
			round?.let { "第 $it 轮" },
		).joinToString(" / "),
		preview = compactPreview(content),
	)

private fun compactPreview(content: String): String {
	val compact = content.replace(Regex("\\s+"), " ").trim()
	return if (compact.length <= TeamMessagePreviewMaxChars) {
		compact
	} else {
		compact.take(TeamMessagePreviewMaxChars - 3).trimEnd() + "..."
	}
}

private fun statusLabel(status: String): String =
	when (status.lowercase()) {
		"idle", "pending", "waiting_config" -> "待配置"
		"running" -> "运行中"
		"completed" -> "已完成"
		"failed" -> "失败"
		"canceled" -> "已取消"
		else -> status
	}

private fun modeLabel(mode: String): String =
	when (mode) {
		"READ_ONLY_TOOL" -> "只读工具"
		"WORKSPACE_TOOL" -> "工作区工具"
		else -> mode
	}

private fun messageTypeLabel(messageType: String): String =
	when (messageType) {
		"route" -> "路由"
		"member_summary" -> "成员摘要"
		"direct_user" -> "用户直发"
		"system" -> "系统"
		else -> messageType
	}
