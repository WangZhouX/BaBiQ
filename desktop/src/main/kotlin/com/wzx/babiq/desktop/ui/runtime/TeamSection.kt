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
import com.wzx.babiq.desktop.protocol.WorkUnitConfigEntry
import com.wzx.babiq.desktop.protocol.WorkUnitConfiguration
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.ProviderState
import com.wzx.babiq.desktop.state.TeamUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import kotlinx.serialization.encodeToString

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
	val configMembers: List<TeamConfigMemberRow> = emptyList(),
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

data class TeamConfigMemberRow(
	val memberId: String,
	val title: String,
	val role: String,
	val task: String,
	val modelLabel: String,
	val modelValue: String,
	val selected: Boolean,
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
	val detail = workUnitDetailModel(config, modelLabel)
	return TeamSectionModel(
		visible = true,
		title = "团队详情 · ${config.name}",
		subtitle = "${statusLabel(config.status)} / ${config.goals.size} 个目标 / 等待手动启动",
		config = detail,
		configMembers = teamConfigMembers(detail),
	)
}

@Composable
fun TeamSection(
	state: TeamUiState,
	modelLabel: String = "未选择模型",
	providerState: ProviderState = ProviderState(),
	onStartWorkUnit: (String) -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onUpdateWorkUnitConfig: (String, String) -> Unit = { _, _ -> },
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
				TeamConfigPanel(
					detail = config,
					members = model.configMembers,
					providerState = providerState,
					onStart = onStartWorkUnit,
					onUpdateGoal = onUpdateWorkUnitGoal,
					onUpdateConfig = onUpdateWorkUnitConfig,
				)
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
private fun TeamConfigPanel(
	detail: WorkUnitDetailModel,
	members: List<TeamConfigMemberRow>,
	providerState: ProviderState,
	onStart: (String) -> Unit,
	onUpdateGoal: (String, String, String) -> Unit,
	onUpdateConfig: (String, String) -> Unit,
) {
	var selectedMemberId by remember(detail.workUnitId, members) {
		mutableStateOf(members.firstOrNull { it.selected }?.memberId ?: members.firstOrNull()?.memberId)
	}
	val selectedMember = members.firstOrNull { it.memberId == selectedMemberId } ?: members.firstOrNull()
	var draftTask by remember(detail.workUnitId, selectedMember?.memberId, selectedMember?.task) {
		mutableStateOf(selectedMember?.task.orEmpty())
	}
	var selectedModelValues by remember(detail.workUnitId, members) {
		mutableStateOf(members.associate { it.memberId to it.modelValue })
	}
	var draftGoal by remember(detail.editableGoalId, detail.editableGoalText) {
		mutableStateOf(detail.editableGoalText ?: "")
	}
	val modelOptions = nodeModelOptions(providerState, detail.modelLabel)
	val selectedModelValue = selectedMember?.let { selectedModelValues[it.memberId] ?: it.modelValue }
	val memberChanged = selectedMember != null &&
		draftTask.isNotBlank() &&
		(draftTask != selectedMember.task || selectedModelValue != selectedMember.modelValue)
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("团队目标", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		detail.editableGoalId?.let { goalId ->
			OutlinedTextField(
				value = draftGoal,
				onValueChange = { draftGoal = it },
				label = { Text("当前待执行目标") },
				modifier = Modifier.fillMaxWidth(),
				minLines = 2,
				maxLines = 4,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				Button(
					onClick = { onUpdateGoal(detail.workUnitId, goalId, draftGoal) },
					enabled = draftGoal.isNotBlank() && draftGoal != (detail.editableGoalText ?: ""),
				) {
					Text("保存目标")
				}
				detail.startActionLabel?.let { label ->
					Button(onClick = { onStart(detail.workUnitId) }) { Text(label) }
				}
			}
		}
		Text("成员设置", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		members.forEach { member ->
			TeamConfigMemberRowView(
				row = member.copy(selected = member.memberId == selectedMemberId),
				onSelect = { selectedMemberId = member.memberId },
			)
		}
		selectedMember?.let { member ->
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
					.padding(10.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text("成员设置 · ${member.title}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
				OutlinedTextField(
					value = draftTask,
					onValueChange = { draftTask = it },
					label = { Text("工作内容") },
					modifier = Modifier.fillMaxWidth(),
					minLines = 3,
					maxLines = 6,
				)
				NodeModelSelector(
					selectedLabel = selectedMemberModelLabel(member, selectedModelValues, modelOptions),
					options = modelOptions,
					enabled = modelOptions.isNotEmpty(),
					onSelect = { option ->
						selectedModelValues = selectedModelValues + (member.memberId to option.modelValue)
					},
				)
				Button(
					onClick = {
						onUpdateConfig(
							detail.workUnitId,
							buildTeamConfigJson(
								detail = detail,
								members = members,
								updatedMemberId = member.memberId,
								updatedTask = draftTask,
								selectedModelValues = selectedModelValues,
							),
						)
					},
					enabled = memberChanged,
				) {
					Text("保存成员")
				}
			}
		}
	}
}

@Composable
private fun TeamConfigMemberRowView(
	row: TeamConfigMemberRow,
	onSelect: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (row.selected) BaBiQColors.Accent.copy(alpha = 0.10f) else BaBiQColors.Panel,
				RoundedCornerShape(8.dp),
			)
			.padding(horizontal = 10.dp, vertical = 8.dp)
			.then(Modifier),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		FilterChip(
			selected = row.selected,
			onClick = onSelect,
			label = { Text("${row.title} · ${row.role}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
		)
		Text(row.task, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
		Text(row.modelLabel, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
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

private fun teamConfigMembers(detail: WorkUnitDetailModel): List<TeamConfigMemberRow> {
	val inheritedModel = detail.modelLabel.ifBlank { "未选择模型" }
	val currentGoal = detail.editableGoalText
		?: detail.goals.lastOrNull()?.label?.substringAfter("·")?.trim()
		?: detail.title
	val savedMembers = detail.configuration?.members.orEmpty()
	if (savedMembers.isNotEmpty()) {
		return savedMembers.mapIndexed { index, entry ->
			val modelValue = entry.model?.takeIf { it.isNotBlank() } ?: "inherit"
			TeamConfigMemberRow(
				memberId = entry.id,
				title = entry.name?.takeIf { it.isNotBlank() } ?: entry.id,
				role = entry.role?.takeIf { it.isNotBlank() } ?: "成员",
				task = entry.task?.takeIf { it.isNotBlank() } ?: currentGoal,
				modelLabel = displayTeamModelLabel(modelValue, inheritedModel),
				modelValue = modelValue,
				selected = index == 0,
			)
		}
	}
	val defaults = listOf(
		TeamConfigMemberRow(
			memberId = "leader",
			title = "leader",
			role = "拆解 / 路由",
			task = "拆解目标并协调团队成员：$currentGoal",
			modelLabel = "继承主 Agent · $inheritedModel",
			modelValue = "inherit",
			selected = true,
		),
		TeamConfigMemberRow(
			memberId = "frontend",
			title = "frontend",
			role = "实现 / 工作区工具",
			task = "根据目标修改相关 UI 或代码，并整理变更点",
			modelLabel = "继承主 Agent · $inheritedModel",
			modelValue = "inherit",
			selected = false,
		),
		TeamConfigMemberRow(
			memberId = "tester",
			title = "tester",
			role = "验证 / 只读工具",
			task = "运行相关验证并反馈失败原因",
			modelLabel = "继承主 Agent · $inheritedModel",
			modelValue = "inherit",
			selected = false,
		),
		TeamConfigMemberRow(
			memberId = "reviewer",
			title = "reviewer",
			role = "复核 / 只读工具",
			task = "复核结果是否符合目标并提出剩余风险",
			modelLabel = "继承主 Agent · $inheritedModel",
			modelValue = "inherit",
			selected = false,
		),
	)
	return defaults
}

private fun selectedMemberModelLabel(
	member: TeamConfigMemberRow,
	selectedModelValues: Map<String, String>,
	options: List<OrchestrationNodeModelOption>,
): String {
	val selectedValue = selectedModelValues[member.memberId] ?: member.modelValue
	return options.firstOrNull { it.modelValue == selectedValue }?.label ?: member.modelLabel
}

private fun buildTeamConfigJson(
	detail: WorkUnitDetailModel,
	members: List<TeamConfigMemberRow>,
	updatedMemberId: String,
	updatedTask: String,
	selectedModelValues: Map<String, String>,
): String {
	val entries = members.map { row ->
		WorkUnitConfigEntry(
			id = row.memberId,
			name = row.title,
			role = row.role,
			task = if (row.memberId == updatedMemberId) updatedTask.trim() else row.task,
			model = selectedModelValues[row.memberId] ?: row.modelValue,
		)
	}
	return protocolJson.encodeToString(
		WorkUnitConfiguration(
			nodes = detail.configuration?.nodes.orEmpty(),
			members = entries,
		),
	)
}

private fun displayTeamModelLabel(modelValue: String, inheritedModel: String): String =
	when {
		modelValue.startsWith("provider:") -> modelValue.removePrefix("provider:").replace(":", " / ")
		else -> "继承主 Agent · $inheritedModel"
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
