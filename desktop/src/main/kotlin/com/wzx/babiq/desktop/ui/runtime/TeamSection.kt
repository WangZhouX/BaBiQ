package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
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
	val selectedTeamId: String? = null,
	val teams: List<TeamSwitchRow> = emptyList(),
	val memberNames: List<String> = emptyList(),
	val members: List<TeamMemberRow> = emptyList(),
	val messages: List<TeamMessageRow> = emptyList(),
	val directError: String? = null,
	val sendingDirect: Boolean = false,
	val config: WorkUnitDetailModel? = null,
	val configMembers: List<TeamConfigMemberRow> = emptyList(),
	val removeActionLabel: String? = null,
	val backActionLabel: String? = null,
)

data class TeamSwitchRow(
	val teamId: String,
	val title: String,
	val status: String,
	val selected: Boolean,
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
	val mode: String = "READ_ONLY_TOOL",
	val modeLabel: String = modeLabel(mode),
	val memberLabel: String = "成员：$title",
	val roleLabel: String = "角色：${teamMemberRoleLabel(role)}",
	val permissionLabel: String = "工具权限：$modeLabel",
	val listMeta: String = "$roleLabel · $permissionLabel",
	val detailTitle: String = "成员详情 · $title",
	val detailActionLabel: String = "详情配置",
	val removeActionLabel: String = "删除成员",
	val selected: Boolean,
)

data class TeamConfigMembersDraft(
	val members: List<TeamConfigMemberRow>,
	val selectedMemberId: String?,
	val detailMemberId: String? = null,
)

fun buildTeamSectionModel(
	state: TeamUiState,
	modelLabel: String = "未选择模型",
): TeamSectionModel {
	val config = state.configuringWorkUnit
	if (config != null) {
		val detail = workUnitDetailModel(config, modelLabel)
		val runtimeTeam = state.current
		val selectedAgent = runtimeTeam?.selectedTarget(state.selectedAgent)
		return TeamSectionModel(
			visible = true,
			title = "团队详情 · ${config.name}",
			subtitle = "${statusLabel(config.status)} / ${config.goals.size} 个目标 / 等待手动启动",
			selectedAgent = selectedAgent,
			selectedTeamId = state.selectedTeamId,
			teams = state.teamSwitchRows(),
			memberNames = runtimeTeam?.targetAgentNames().orEmpty(),
			members = runtimeTeam?.members?.map { it.toRow() }.orEmpty(),
			messages = state.messages.takeLast(6).map { it.toRow() },
			directError = state.directError,
			sendingDirect = state.sendingDirect,
			config = detail,
			configMembers = teamConfigMembers(detail),
			removeActionLabel = detail.removeActionLabel,
			backActionLabel = "返回列表",
		)
	}
	if (!state.visible) {
		return TeamSectionModel(false, "", "")
	}
	val team = state.current
	if (team != null) {
		val selectedAgent = team.selectedTarget(state.selectedAgent)
		return TeamSectionModel(
			visible = true,
			title = "团队协作 · ${team.title}",
			subtitle = buildSubtitle(team),
			selectedAgent = selectedAgent,
			selectedTeamId = state.selectedTeamId ?: team.teamId,
			teams = state.teamSwitchRows(),
			memberNames = team.targetAgentNames(),
			members = team.members.map { it.toRow() },
			messages = state.messages.takeLast(6).map { it.toRow() },
			directError = state.directError,
			sendingDirect = state.sendingDirect,
			config = null,
			removeActionLabel = if (team.status.lowercase() in setOf("completed", "failed", "canceled")) "移除" else null,
		)
	}
	return TeamSectionModel(false, "", "")
}

@Composable
fun TeamSection(
	state: TeamUiState,
	modelLabel: String = "未选择模型",
	providerState: ProviderState = ProviderState(),
	onStartWorkUnit: (String) -> Unit = {},
	onRemoveWorkUnit: (String) -> Unit = {},
	onDismissTeam: () -> Unit = {},
	onBackToList: () -> Unit = {},
	onSelectTeam: (String) -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onRenameWorkUnit: (String, String) -> Unit = { _, _ -> },
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
			Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
					Text(model.subtitle, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
				}
				model.backActionLabel?.let { label ->
					TextButton(onClick = onBackToList) { Text(label) }
				}
				model.removeActionLabel?.let { label ->
					TextButton(
						onClick = {
							val workUnitId = model.config?.workUnitId
							if (workUnitId != null) {
								onRemoveWorkUnit(workUnitId)
							} else {
								onDismissTeam()
							}
						},
					) {
						Text(label)
					}
				}
			}
			model.config?.let { config ->
				TeamConfigPanel(
					detail = config,
					members = model.configMembers,
					providerState = providerState,
					onStart = onStartWorkUnit,
					onUpdateGoal = onUpdateWorkUnitGoal,
					onRenameWorkUnit = onRenameWorkUnit,
					onUpdateConfig = onUpdateWorkUnitConfig,
				)
			}
			if (model.teams.size > 1) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
					model.teams.forEach { team ->
						FilterChip(
							selected = team.selected,
							onClick = { onSelectTeam(team.teamId) },
							label = {
								Text(
									"${team.title} / ${team.status}",
									maxLines = 1,
									overflow = TextOverflow.Ellipsis,
								)
							},
						)
					}
				}
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
	onRenameWorkUnit: (String, String) -> Unit,
	onUpdateConfig: (String, String) -> Unit,
) {
	var draftMembers by remember(detail.workUnitId, members) {
		mutableStateOf(members)
	}
	var selectedMemberId by remember(detail.workUnitId, members) {
		mutableStateOf(members.firstOrNull { it.selected }?.memberId ?: members.firstOrNull()?.memberId)
	}
	var detailMemberId by remember(detail.workUnitId, members) {
		mutableStateOf<String?>(null)
	}
	val effectiveSelectedMemberId = selectedMemberId
		?.takeIf { candidate -> draftMembers.any { it.memberId == candidate } }
		?: draftMembers.firstOrNull()?.memberId
	val detailMember = detailMemberId?.let { memberId -> draftMembers.firstOrNull { it.memberId == memberId } }
	var draftTask by remember(detail.workUnitId, detailMember?.memberId, detailMember?.task) {
		mutableStateOf(detailMember?.task.orEmpty())
	}
	var draftMemberTitle by remember(detail.workUnitId, detailMember?.memberId, detailMember?.title) {
		mutableStateOf(detailMember?.title.orEmpty())
	}
	var draftMemberRole by remember(detail.workUnitId, detailMember?.memberId, detailMember?.role) {
		mutableStateOf(detailMember?.role.orEmpty())
	}
	var selectedModelValues by remember(detail.workUnitId, draftMembers) {
		mutableStateOf(draftMembers.associate { it.memberId to it.modelValue })
	}
	var selectedModeValues by remember(detail.workUnitId, draftMembers) {
		mutableStateOf(draftMembers.associate { it.memberId to it.mode })
	}
	var draftGoal by remember(detail.editableGoalId, detail.editableGoalText) {
		mutableStateOf(detail.editableGoalText ?: "")
	}
	var draftWorkUnitName by remember(detail.workUnitId, detail.name) {
		mutableStateOf(detail.name)
	}
	val modelOptions = nodeModelOptions(providerState, detail.modelLabel)
	val selectedModelValue = detailMember?.let { selectedModelValues[it.memberId] ?: it.modelValue }
	val selectedModeValue = detailMember?.let { selectedModeValues[it.memberId] ?: it.mode }
	val memberChanged = detailMember != null &&
		draftMemberTitle.isNotBlank() &&
		draftMemberRole.isNotBlank() &&
		draftTask.isNotBlank() &&
		(draftMemberTitle != detailMember.title ||
			draftMemberRole != detailMember.role ||
			draftTask != detailMember.task ||
			selectedModelValue != detailMember.modelValue ||
			selectedModeValue != detailMember.mode)
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		if (detailMember != null) {
			TeamConfigMemberDetailPanel(
				detail = detail,
				member = detailMember,
				draftMembers = draftMembers,
				draftMemberTitle = draftMemberTitle,
				draftMemberRole = draftMemberRole,
				draftTask = draftTask,
				selectedModelValues = selectedModelValues,
				selectedModeValues = selectedModeValues,
				modelOptions = modelOptions,
				selectedModelValue = selectedModelValue,
				selectedModeValue = selectedModeValue,
				memberChanged = memberChanged,
				onBack = { detailMemberId = null },
				onDraftTitleChange = { draftMemberTitle = it },
				onDraftRoleChange = { draftMemberRole = it },
				onDraftTaskChange = { draftTask = it },
				onSelectModel = { memberId, modelValue ->
					selectedModelValues = selectedModelValues + (memberId to modelValue)
				},
				onSelectMode = { memberId, mode ->
					selectedModeValues = selectedModeValues + (memberId to mode)
				},
				onRemove = {
					val draft = removeTeamConfigMemberDraft(
						TeamConfigMembersDraft(draftMembers, effectiveSelectedMemberId, detailMemberId),
						detailMember.memberId,
					)
					draftMembers = draft.members
					selectedMemberId = draft.selectedMemberId
					detailMemberId = draft.detailMemberId
					onUpdateConfig(detail.workUnitId, buildTeamConfigJson(detail, draft.members))
				},
				onSave = {
					val updatedMembers = updateTeamConfigMember(
						members = draftMembers,
						memberId = detailMember.memberId,
						title = draftMemberTitle,
						role = draftMemberRole,
						task = draftTask,
						modelValue = selectedModelValue ?: detailMember.modelValue,
						mode = selectedModeValue ?: detailMember.mode,
						inheritedModel = detail.modelLabel,
					)
					draftMembers = updatedMembers
					selectedMemberId = detailMember.memberId
					detailMemberId = detailMember.memberId
					onUpdateConfig(
						detail.workUnitId,
						buildTeamConfigJson(
							detail = detail,
							members = updatedMembers,
						),
					)
				},
			)
			return@Column
		}
		OutlinedTextField(
			value = draftWorkUnitName,
			onValueChange = { draftWorkUnitName = it },
			label = { Text("团队名称") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		Button(
			onClick = { onRenameWorkUnit(detail.workUnitId, draftWorkUnitName.trim()) },
			enabled = draftWorkUnitName.trim().isNotBlank() && draftWorkUnitName.trim() != detail.name,
		) {
			Text("保存名称")
		}
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
		if (detail.editableGoalId == null) {
			detail.startActionLabel?.let { label ->
				Button(onClick = { onStart(detail.workUnitId) }) { Text(label) }
			}
		}
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text("成员列表", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
			TextButton(
				onClick = {
					val draft = addTeamConfigMemberDraft(
						TeamConfigMembersDraft(draftMembers, effectiveSelectedMemberId, detailMemberId),
						detail,
					)
					draftMembers = draft.members
					selectedMemberId = draft.selectedMemberId
					detailMemberId = draft.detailMemberId
					onUpdateConfig(detail.workUnitId, buildTeamConfigJson(detail, draft.members))
				},
			) {
				Text("添加成员")
			}
		}
		draftMembers.forEach { member ->
			TeamConfigMemberRowView(
				row = member.copy(selected = member.memberId == effectiveSelectedMemberId),
				canRemove = draftMembers.size > 1,
				onOpenDetail = {
					val draft = openTeamConfigMemberDetail(
						TeamConfigMembersDraft(draftMembers, effectiveSelectedMemberId, detailMemberId),
						member.memberId,
					)
					selectedMemberId = draft.selectedMemberId
					detailMemberId = draft.detailMemberId
				},
				onRemove = {
					val draft = removeTeamConfigMemberDraft(
						TeamConfigMembersDraft(draftMembers, effectiveSelectedMemberId, detailMemberId),
						member.memberId,
					)
					draftMembers = draft.members
					selectedMemberId = draft.selectedMemberId
					detailMemberId = draft.detailMemberId
					onUpdateConfig(detail.workUnitId, buildTeamConfigJson(detail, draft.members))
				},
			)
		}
	}
}

@Composable
private fun TeamConfigMemberDetailPanel(
	detail: WorkUnitDetailModel,
	member: TeamConfigMemberRow,
	draftMembers: List<TeamConfigMemberRow>,
	draftMemberTitle: String,
	draftMemberRole: String,
	draftTask: String,
	selectedModelValues: Map<String, String>,
	selectedModeValues: Map<String, String>,
	modelOptions: List<OrchestrationNodeModelOption>,
	selectedModelValue: String?,
	selectedModeValue: String?,
	memberChanged: Boolean,
	onBack: () -> Unit,
	onDraftTitleChange: (String) -> Unit,
	onDraftRoleChange: (String) -> Unit,
	onDraftTaskChange: (String) -> Unit,
	onSelectModel: (String, String) -> Unit,
	onSelectMode: (String, String) -> Unit,
	onRemove: () -> Unit,
	onSave: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text(member.detailTitle, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			TextButton(onClick = onBack) {
				Text("返回成员列表")
			}
		}
		TextButton(
			onClick = onRemove,
			enabled = draftMembers.size > 1,
		) {
			Text(member.removeActionLabel)
		}
		OutlinedTextField(
			value = draftMemberTitle,
			onValueChange = onDraftTitleChange,
			label = { Text("成员名称") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draftMemberRole,
			onValueChange = onDraftRoleChange,
			label = { Text("成员角色") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		OutlinedTextField(
			value = draftTask,
			onValueChange = onDraftTaskChange,
			label = { Text("工作内容") },
			modifier = Modifier.fillMaxWidth(),
			minLines = 3,
			maxLines = 6,
		)
		NodeModelSelector(
			selectedLabel = selectedMemberModelLabel(member, selectedModelValues, modelOptions),
			options = modelOptions,
			enabled = modelOptions.isNotEmpty(),
			onSelect = { option -> onSelectModel(member.memberId, option.modelValue) },
		)
		Text("工具权限", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
			FilterChip(
				selected = selectedModeValue == "READ_ONLY_TOOL",
				onClick = { onSelectMode(member.memberId, "READ_ONLY_TOOL") },
				label = { Text("只读工具") },
			)
			FilterChip(
				selected = selectedModeValue == "WORKSPACE_TOOL",
				onClick = { onSelectMode(member.memberId, "WORKSPACE_TOOL") },
				label = { Text("全工具") },
			)
		}
		Button(
			onClick = onSave,
			enabled = memberChanged && (selectedModelValue != null || selectedModeValue != null),
		) {
			Text("保存成员")
		}
	}
}

@Composable
private fun TeamConfigMemberRowView(
	row: TeamConfigMemberRow,
	canRemove: Boolean,
	onOpenDetail: () -> Unit,
	onRemove: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onOpenDetail)
			.background(
				if (row.selected) BaBiQColors.Accent.copy(alpha = 0.10f) else BaBiQColors.Panel,
				RoundedCornerShape(8.dp),
			)
			.padding(horizontal = 10.dp, vertical = 8.dp)
			.then(Modifier),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			FilterChip(
				selected = row.selected,
				onClick = onOpenDetail,
				label = { Text(row.memberLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			)
			Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				TextButton(onClick = onOpenDetail) {
					Text(row.detailActionLabel)
				}
				TextButton(onClick = onRemove, enabled = canRemove) {
					Text(row.removeActionLabel)
				}
			}
		}
		Text(row.listMeta, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
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

private fun TeamUiState.teamSwitchRows(): List<TeamSwitchRow> =
	teams.map { team ->
		TeamSwitchRow(
			teamId = team.teamId,
			title = team.title,
			status = statusLabel(team.status),
			selected = team.teamId == (selectedTeamId ?: current?.teamId),
		)
	}

private fun ThreadItem.Team.targetAgentNames(): List<String> =
	listOf("leader") + members.map { it.name }.filterNot { it == "leader" }

private fun ThreadItem.Team.selectedTarget(selectedAgent: String?): String =
	selectedAgent
		?.takeIf { it in targetAgentNames() }
		?: "leader"

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
				mode = normalizedTeamMode(entry.mode),
				modeLabel = modeLabel(normalizedTeamMode(entry.mode)),
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
			role = "实现",
			task = "根据目标修改相关 UI 或代码，并整理变更点",
			modelLabel = "继承主 Agent · $inheritedModel",
			modelValue = "inherit",
			mode = "WORKSPACE_TOOL",
			selected = false,
		),
		TeamConfigMemberRow(
			memberId = "tester",
			title = "tester",
			role = "验证",
			task = "运行相关验证并反馈失败原因",
			modelLabel = "继承主 Agent · $inheritedModel",
			modelValue = "inherit",
			selected = false,
		),
		TeamConfigMemberRow(
			memberId = "reviewer",
			title = "reviewer",
			role = "复核",
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

private fun normalizedTeamMode(mode: String?): String =
	if (mode.equals("WORKSPACE_TOOL", ignoreCase = true)) "WORKSPACE_TOOL" else "READ_ONLY_TOOL"

internal fun addTeamConfigMember(
	members: List<TeamConfigMemberRow>,
	detail: WorkUnitDetailModel,
): List<TeamConfigMemberRow> {
	val inheritedModel = detail.modelLabel.ifBlank { "未选择模型" }
	val nextIndex = generateSequence(members.size + 1) { it + 1 }
		.first { index -> members.none { member -> member.memberId == "member_$index" } }
	val memberId = "member_$nextIndex"
	return members + TeamConfigMemberRow(
		memberId = memberId,
		title = memberId,
		role = "member",
		task = "补充这个成员的任务",
		modelLabel = displayTeamModelLabel("inherit", inheritedModel),
		modelValue = "inherit",
		mode = "READ_ONLY_TOOL",
		modeLabel = modeLabel("READ_ONLY_TOOL"),
		selected = false,
	)
}

internal fun addTeamConfigMemberDraft(
	draft: TeamConfigMembersDraft,
	detail: WorkUnitDetailModel,
): TeamConfigMembersDraft {
	val nextMembers = addTeamConfigMember(draft.members, detail)
	val nextMemberId = nextMembers.lastOrNull()?.memberId ?: draft.selectedMemberId
	return TeamConfigMembersDraft(
		members = nextMembers,
		selectedMemberId = nextMemberId,
		detailMemberId = nextMemberId,
	)
}

internal fun openTeamConfigMemberDetail(
	draft: TeamConfigMembersDraft,
	memberId: String,
): TeamConfigMembersDraft =
	if (draft.members.any { it.memberId == memberId }) {
		draft.copy(selectedMemberId = memberId, detailMemberId = memberId)
	} else {
		draft
	}

internal fun closeTeamConfigMemberDetail(draft: TeamConfigMembersDraft): TeamConfigMembersDraft =
	draft.copy(detailMemberId = null)

internal fun removeTeamConfigMemberDraft(
	draft: TeamConfigMembersDraft,
	memberId: String,
): TeamConfigMembersDraft {
	val nextMembers = removeTeamConfigMember(draft.members, memberId)
	if (nextMembers.size == draft.members.size) {
		return draft
	}
	val nextIds = nextMembers.map { it.memberId }.toSet()
	val nextSelectedMemberId = draft.selectedMemberId
		?.takeIf { it in nextIds }
		?: nextMembers.firstOrNull()?.memberId
	val nextDetailMemberId = draft.detailMemberId
		?.takeIf { it in nextIds }
	return TeamConfigMembersDraft(
		members = nextMembers,
		selectedMemberId = nextSelectedMemberId,
		detailMemberId = nextDetailMemberId,
	)
}

internal fun updateTeamConfigMember(
	members: List<TeamConfigMemberRow>,
	memberId: String,
	title: String,
	role: String,
	task: String,
	modelValue: String,
	mode: String,
	inheritedModel: String,
): List<TeamConfigMemberRow> =
	members.map { row ->
		if (row.memberId != memberId) {
			row
		} else {
			val normalizedMode = normalizedTeamMode(mode)
			val normalizedModel = modelValue.ifBlank { "inherit" }
			row.copy(
				title = title.trim(),
				role = role.trim(),
				task = task.trim(),
				modelValue = normalizedModel,
				modelLabel = displayTeamModelLabel(normalizedModel, inheritedModel.ifBlank { "未选择模型" }),
				mode = normalizedMode,
				modeLabel = modeLabel(normalizedMode),
			)
		}
	}

internal fun removeTeamConfigMember(
	members: List<TeamConfigMemberRow>,
	memberId: String,
): List<TeamConfigMemberRow> {
	if (members.size <= 1) {
		return members
	}
	return members.filterNot { it.memberId == memberId }
}

internal fun buildTeamConfigJson(
	detail: WorkUnitDetailModel,
	members: List<TeamConfigMemberRow>,
): String {
	val entries = members.map { row ->
		WorkUnitConfigEntry(
			id = row.memberId,
			name = row.title,
			role = row.role,
			task = row.task,
			model = row.modelValue,
			mode = normalizedTeamMode(row.mode),
			toolNames = teamToolNames(row.mode),
			writeScopes = teamWriteScopes(row.mode, detail),
		)
	}
	return protocolJson.encodeToString(
		WorkUnitConfiguration(
			nodes = detail.configuration?.nodes.orEmpty(),
			members = entries,
		),
	)
}

private fun teamToolNames(mode: String): List<String> =
	if (normalizedTeamMode(mode) == "WORKSPACE_TOOL") {
		listOf("read_file", "list_dir", "grep", "write_file", "apply_patch")
	} else {
		listOf("read_file", "list_dir", "grep")
	}

private fun teamWriteScopes(mode: String, detail: WorkUnitDetailModel): List<String> =
	if (normalizedTeamMode(mode) == "WORKSPACE_TOOL") {
		detail.cwd.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
	} else {
		emptyList()
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

private fun teamMemberRoleLabel(role: String): String {
	val cleaned = role.trim()
		.removeSuffix("/ 工作区工具").trim()
		.removeSuffix("/ 只读工具").trim()
		.removeSuffix("／工作区工具").trim()
		.removeSuffix("／只读工具").trim()
		.removeSuffix("工作区工具").trim()
		.removeSuffix("只读工具").trim()
		.removeSuffix("/").trim()
		.removeSuffix("／").trim()
	return cleaned.ifBlank { "成员" }
}

private fun messageTypeLabel(messageType: String): String =
	when (messageType) {
		"route" -> "路由"
		"member_summary" -> "成员摘要"
		"direct_user" -> "用户直发"
		"system" -> "系统"
		else -> messageType
	}
