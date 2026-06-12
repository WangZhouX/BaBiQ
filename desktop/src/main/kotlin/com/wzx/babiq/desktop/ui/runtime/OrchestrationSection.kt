package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitConfigEntry
import com.wzx.babiq.desktop.protocol.WorkUnitConfiguration
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.OrchestrationUiState
import com.wzx.babiq.desktop.state.ProviderState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import kotlinx.serialization.encodeToString

private const val FlowSummaryMaxChars = 120

data class OrchestrationSectionModel(
	val visible: Boolean,
	val title: String,
	val subtitle: String,
	val nodes: List<OrchestrationNodeRow>,
	val summaryPreview: String? = null,
	val config: WorkUnitDetailModel? = null,
	val configNodes: List<OrchestrationConfigNodeRow> = emptyList(),
	val selectedNodeSettings: OrchestrationNodeSettingsModel? = null,
	val addNodeActionLabel: String? = null,
	val removeActionLabel: String? = null,
	val editModeTitle: String? = null,
)

data class OrchestrationNodeRow(
	val icon: String,
	val title: String,
	val meta: String,
	val detail: String,
	val active: Boolean,
)

data class OrchestrationConfigNodeRow(
	val nodeId: String,
	val title: String,
	val role: String,
	val task: String,
	val modelLabel: String,
	val modelValue: String,
	val modeLabel: String,
	val modeValue: String = modeLabel,
	val selected: Boolean,
	val removable: Boolean,
)

data class OrchestrationNodeSettingsModel(
	val nodeId: String,
	val title: String,
	val task: String,
	val modelLabel: String,
	val modelValue: String,
	val removable: Boolean,
)

data class OrchestrationNodeModelOption(
	val providerId: String?,
	val modelId: String?,
	val label: String,
	val modelValue: String,
)

fun buildOrchestrationSectionModel(
	state: OrchestrationUiState,
	modelLabel: String = "未选择模型",
): OrchestrationSectionModel {
	val item = state.current
	if (item != null) {
		return OrchestrationSectionModel(
			visible = true,
			title = "流程编排 · ${item.title}",
			subtitle = "${topologyLabel(item.topology)} / ${statusLabel(item.status)} / ${approvalLabel(item)}",
			nodes = item.nodes.map(::nodeRow),
			summaryPreview = compactFlowSummary(item.summary),
			config = null,
			removeActionLabel = runtimeRemoveActionLabel(item.status),
		)
	}
	val config = state.configuringWorkUnit ?: return OrchestrationSectionModel(false, "", "", emptyList())
	val detail = workUnitDetailModel(config, modelLabel)
	val configNodes = configDraftNodes(detail)
	val selectedNode = configNodes.firstOrNull { it.nodeId == "start" } ?: configNodes.firstOrNull()
	return OrchestrationSectionModel(
		visible = true,
		title = "编排详情 · ${config.name}",
		subtitle = "${statusLabel(config.status)} / ${config.goals.size} 个目标 / 等待手动启动",
		nodes = emptyList(),
		config = detail,
		configNodes = configNodes,
		selectedNodeSettings = selectedNode?.let(::nodeSettingsModel),
		addNodeActionLabel = "添加节点",
		removeActionLabel = detail.removeActionLabel,
		editModeTitle = "编排 · 编辑模式",
	)
}

@Composable
fun OrchestrationSection(
	state: OrchestrationUiState,
	modelLabel: String = "未选择模型",
	providerState: ProviderState = ProviderState(),
	onStartWorkUnit: (String) -> Unit = {},
	onRemoveWorkUnit: (String) -> Unit = {},
	onDismissOrchestration: () -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onUpdateWorkUnitConfig: (String, String) -> Unit = { _, _ -> },
) {
	val model = buildOrchestrationSectionModel(state, modelLabel)
	if (!model.visible) {
		return
	}
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
					Text(model.subtitle, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
				}
				model.removeActionLabel?.let { label ->
					TextButton(
						onClick = {
							val workUnitId = model.config?.workUnitId
							if (workUnitId != null) {
								onRemoveWorkUnit(workUnitId)
							} else {
								onDismissOrchestration()
							}
						},
					) {
						Text(label)
					}
				}
			}
			model.config?.let { config ->
				OrchestrationConfigPanel(
					detail = config,
					nodes = model.configNodes,
					defaultSettings = model.selectedNodeSettings,
					addNodeActionLabel = model.addNodeActionLabel,
					editModeTitle = model.editModeTitle,
					providerState = providerState,
					onStart = onStartWorkUnit,
					onUpdateGoal = onUpdateWorkUnitGoal,
					onUpdateConfig = onUpdateWorkUnitConfig,
				)
			}
			model.nodes.forEach { row -> OrchestrationNodeRowView(row) }
			model.summaryPreview?.let { preview -> FlowSummaryPreview(preview) }
		}
	}
}

@Composable
private fun OrchestrationConfigPanel(
	detail: WorkUnitDetailModel,
	nodes: List<OrchestrationConfigNodeRow>,
	defaultSettings: OrchestrationNodeSettingsModel?,
	addNodeActionLabel: String?,
	editModeTitle: String?,
	providerState: ProviderState,
	onStart: (String) -> Unit,
	onUpdateGoal: (String, String, String) -> Unit,
	onUpdateConfig: (String, String) -> Unit,
) {
	var draftNodes by remember(detail.workUnitId, nodes) {
		mutableStateOf(nodes)
	}
	var selectedNodeId by remember(detail.workUnitId, nodes) {
		mutableStateOf(defaultSettings?.nodeId ?: nodes.firstOrNull()?.nodeId)
	}
	val selectedRow = draftNodes.firstOrNull { it.nodeId == selectedNodeId } ?: draftNodes.firstOrNull()
	val settings = selectedRow?.let(::nodeSettingsModel)
	var draftTask by remember(detail.workUnitId, settings?.nodeId, settings?.task) {
		mutableStateOf(settings?.task.orEmpty())
	}
	var selectedModelValues by remember(detail.workUnitId, nodes) {
		mutableStateOf(draftNodes.associate { it.nodeId to it.modelValue })
	}
	val modelOptions = nodeModelOptions(providerState, detail.modelLabel)
	val selectedModelValue = settings?.let { selectedModelValues[it.nodeId] ?: it.modelValue }
	val hasNodeChanges = settings != null &&
		draftTask.isNotBlank() &&
		(draftTask != settings.task || selectedModelValue != settings.modelValue)
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		OrchestrationTopologyFrame(
			title = editModeTitle ?: "编排 · 编辑模式",
			addNodeActionLabel = addNodeActionLabel,
			nodes = draftNodes,
			selectedNodeId = selectedNodeId,
			onSelect = { selectedNodeId = it },
			onAddNode = addNodeActionLabel?.let {
				{
					val updatedNodes = addOrchestrationDraftNode(draftNodes, detail.modelLabel)
					val addedNode = updatedNodes.firstOrNull { candidate ->
						draftNodes.none { existing -> existing.nodeId == candidate.nodeId }
					}
					draftNodes = updatedNodes
					if (addedNode != null) {
						selectedNodeId = addedNode.nodeId
					}
					val updatedNodeIds = updatedNodes.map { it.nodeId }.toSet()
					val nextModelValues = updatedNodes.associate { row -> row.nodeId to row.modelValue } +
						selectedModelValues.filterKeys { it in updatedNodeIds }
					selectedModelValues = nextModelValues
					onUpdateConfig(
						detail.workUnitId,
						buildOrchestrationConfigJson(
							detail = detail,
							nodes = updatedNodes,
							selectedModelValues = nextModelValues,
						),
					)
				}
			},
		)
		settings?.let { nodeSettings ->
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
					.padding(10.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
					Text(nodeSettings.title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
					TextButton(onClick = {}, enabled = false) { Text("删除节点") }
				}
				OutlinedTextField(
					value = draftTask,
					onValueChange = { draftTask = it },
					label = { Text("任务") },
					modifier = Modifier.fillMaxWidth(),
					minLines = 3,
					maxLines = 6,
				)
				NodeModelSelector(
					selectedLabel = selectedNodeModelLabel(
						nodeSettings = nodeSettings,
						selectedModelValues = selectedModelValues,
						options = modelOptions,
					),
					options = modelOptions,
					enabled = nodeModelSelectionEnabled(nodeSettings),
					onSelect = { option ->
						selectedModelValues = selectedModelValues + (nodeSettings.nodeId to option.modelValue)
					},
				)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
					Button(
						onClick = {
							val updatedNodes = draftNodes.map { row ->
								if (row.nodeId == nodeSettings.nodeId) {
									row.copy(
										task = draftTask.trim(),
										modelValue = selectedModelValues[row.nodeId] ?: row.modelValue,
										modelLabel = selectedNodeModelLabel(nodeSettings, selectedModelValues, modelOptions),
									)
								} else {
									row
								}
							}
							draftNodes = updatedNodes
							if (nodeSettings.nodeId == "start" && draftTask != nodeSettings.task) {
								detail.editableGoalId?.let { goalId ->
									onUpdateGoal(detail.workUnitId, goalId, draftTask)
								}
							}
							onUpdateConfig(
								detail.workUnitId,
								buildOrchestrationConfigJson(
									detail = detail,
									nodes = updatedNodes,
									updatedNodeId = nodeSettings.nodeId,
									updatedTask = draftTask,
									selectedModelValues = selectedModelValues,
								),
							)
						},
						enabled = hasNodeChanges,
					) {
						Text("保存节点")
					}
					detail.startActionLabel?.let { label ->
						Button(onClick = { onStart(detail.workUnitId) }) { Text(label) }
					}
				}
			}
		}
		Text("目标队列", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		if (detail.goals.isEmpty()) {
			Text("暂无目标", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		} else {
			detail.goals.forEach { goal ->
				Text(goal.label, style = MaterialTheme.typography.bodySmall)
			}
		}
	}
}

@Composable
fun NodeModelSelector(
	selectedLabel: String,
	options: List<OrchestrationNodeModelOption>,
	enabled: Boolean,
	onSelect: (OrchestrationNodeModelOption) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	Column {
		OutlinedButton(
			onClick = { expanded = true },
			enabled = enabled && options.isNotEmpty(),
		) {
			Text(selectedLabel)
		}
		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
		) {
			options.forEach { option ->
				DropdownMenuItem(
					text = { Text(option.label) },
					onClick = {
						expanded = false
						onSelect(option)
					},
				)
			}
		}
	}
}

@Composable
private fun OrchestrationTopologyFrame(
	title: String,
	addNodeActionLabel: String?,
	nodes: List<OrchestrationConfigNodeRow>,
	selectedNodeId: String?,
	onSelect: (String) -> Unit,
	onAddNode: (() -> Unit)? = null,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Background, RoundedCornerShape(10.dp))
			.border(1.dp, BaBiQColors.Border, RoundedCornerShape(10.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				addNodeActionLabel?.let { label ->
					OutlinedButton(onClick = { onAddNode?.invoke() }, enabled = onAddNode != null) { Text("+ $label") }
				}
				Text("收起 ◀", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			}
		}
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(1.dp)
				.background(BaBiQColors.Border),
		)
		OrchestrationTopologyEditor(
			nodes = nodes,
			selectedNodeId = selectedNodeId,
			onSelect = onSelect,
		)
	}
}

@Composable
private fun OrchestrationTopologyEditor(
	nodes: List<OrchestrationConfigNodeRow>,
	selectedNodeId: String?,
	onSelect: (String) -> Unit,
) {
	val startNode = nodes.firstOrNull { it.nodeId == "start" }
	val endNode = nodes.firstOrNull { it.nodeId == "end" }
	val middleNodes = nodes.filter { it.nodeId != "start" && it.nodeId != "end" }
	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		startNode?.let { node ->
			OrchestrationTerminalNode(
				node = node.copy(selected = node.nodeId == selectedNodeId),
				onSelect = { onSelect(node.nodeId) },
			)
		}
		if (startNode != null && (middleNodes.isNotEmpty() || endNode != null)) {
			VerticalConnector()
		}
		middleNodes.forEachIndexed { index, node ->
			OrchestrationConfigNodeCard(
				node = node.copy(selected = node.nodeId == selectedNodeId),
				onSelect = { onSelect(node.nodeId) },
			)
			if (index < middleNodes.lastIndex || endNode != null) {
				VerticalConnector()
			}
		}
		endNode?.let { node ->
			OrchestrationTerminalNode(
				node = node.copy(selected = node.nodeId == selectedNodeId),
				onSelect = { onSelect(node.nodeId) },
			)
		}
	}
}

@Composable
private fun VerticalConnector() {
	Box(
		modifier = Modifier
			.size(width = 1.dp, height = 18.dp)
			.background(BaBiQColors.Border),
	)
}

@Composable
private fun OrchestrationTerminalNode(
	node: OrchestrationConfigNodeRow,
	onSelect: () -> Unit,
) {
	Box(
		modifier = Modifier
			.background(BaBiQColors.Panel, RoundedCornerShape(999.dp))
			.border(
				1.dp,
				if (node.selected) BaBiQColors.Accent else BaBiQColors.Border,
				RoundedCornerShape(999.dp),
			)
			.clickable(onClick = onSelect)
			.padding(horizontal = 18.dp, vertical = 7.dp),
	) {
		Text(node.title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
	}
}

@Composable
private fun OrchestrationConfigNodeCard(
	node: OrchestrationConfigNodeRow,
	onSelect: () -> Unit,
	modifier: Modifier = Modifier.fillMaxWidth(),
) {
	Column(
		modifier = modifier
			.background(BaBiQColors.Background, RoundedCornerShape(10.dp))
			.border(
				1.dp,
				if (node.selected) BaBiQColors.Accent else BaBiQColors.Border,
				RoundedCornerShape(10.dp),
			)
			.clickable(onClick = onSelect)
			.padding(horizontal = 12.dp, vertical = 10.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				Box(
					modifier = Modifier
						.size(8.dp)
						.background(if (node.selected) BaBiQColors.Accent else BaBiQColors.Muted, CircleShape),
				)
				Text(node.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
			}
			if (node.removable) {
				Text("×", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
			}
		}
		Text("${node.role} / ${node.modeLabel}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(
			node.task,
			style = MaterialTheme.typography.labelSmall,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun OrchestrationNodeRowView(row: OrchestrationNodeRow) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (row.active) BaBiQColors.Accent.copy(alpha = 0.10f) else BaBiQColors.Accent.copy(alpha = 0.06f),
				RoundedCornerShape(6.dp),
			)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
			Text(row.icon, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
			Text(row.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
		}
		Text(row.meta, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(
			row.detail,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun FlowSummaryPreview(preview: String) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text("流程摘要", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		Text(
			preview,
			style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

private fun configDraftNodes(detail: WorkUnitDetailModel): List<OrchestrationConfigNodeRow> {
	val inheritedModel = detail.modelLabel.ifBlank { "未选择模型" }
	val currentGoal = currentConfigGoal(detail)
	val savedNodes = detail.configuration?.nodes.orEmpty()
	if (savedNodes.isNotEmpty()) {
		return normalizedConfigNodes(
			entries = savedNodes,
			currentGoal = currentGoal,
			inheritedModel = inheritedModel,
		)
	}
	val middleNodes = explicitNodeIdsFromGoal(currentGoal).map { nodeId ->
		defaultConfigNodeFor(
			nodeId = nodeId,
			currentGoal = currentGoal,
			inheritedModel = inheritedModel,
		)
	}
	return listOf(startConfigNode(currentGoal)) + middleNodes + endConfigNode()
}

fun addOrchestrationDraftNode(
	nodes: List<OrchestrationConfigNodeRow>,
	inheritedModelLabel: String,
): List<OrchestrationConfigNodeRow> {
	val existingIds = nodes.map { it.nodeId }.toSet()
	val nodeId = generateSequence(1) { it + 1 }
		.map { "node_$it" }
		.first { it !in existingIds }
	val newNode = configNode(
		nodeId = nodeId,
		title = nodeId,
		role = "自定义节点",
		task = "补充这个节点的任务",
		modeLabel = modeLabel("READ_ONLY_TOOL"),
		modeValue = "READ_ONLY_TOOL",
		modelLabel = inheritedModelLabel.ifBlank { "未选择模型" },
		selected = true,
		removable = true,
	)
	val cleared = nodes.map { it.copy(selected = false) }
	val endIndex = cleared.indexOfFirst { it.nodeId == "end" }
	if (endIndex < 0) {
		return cleared + newNode
	}
	return cleared.take(endIndex) + newNode + cleared.drop(endIndex)
}

private fun currentConfigGoal(detail: WorkUnitDetailModel): String =
	detail.editableGoalText
		?: detail.goals.lastOrNull()?.label?.substringAfter("路")?.trim()
		?: detail.title

private fun normalizedConfigNodes(
	entries: List<WorkUnitConfigEntry>,
	currentGoal: String,
	inheritedModel: String,
): List<OrchestrationConfigNodeRow> {
	val start = entries.firstOrNull { it.id.equals("start", ignoreCase = true) }
	val end = entries.firstOrNull { it.id.equals("end", ignoreCase = true) }
	val middle = entries.filterNot {
		it.id.equals("start", ignoreCase = true) || it.id.equals("end", ignoreCase = true)
	}
	return listOf(
		configNodeFromEntry(start, "start", currentGoal, inheritedModel, selected = true, removable = false),
	) + middle.map { entry ->
		configNodeFromEntry(entry, entry.id, currentGoal, inheritedModel, selected = false, removable = true)
	} + listOf(
		configNodeFromEntry(end, "end", currentGoal, inheritedModel, selected = false, removable = false),
	)
}

private fun configNodeFromEntry(
	entry: WorkUnitConfigEntry?,
	fallbackId: String,
	currentGoal: String,
	inheritedModel: String,
	selected: Boolean,
	removable: Boolean,
): OrchestrationConfigNodeRow {
	val nodeId = entry?.id?.takeIf { it.isNotBlank() } ?: fallbackId
	val modelValue = entry?.model?.takeIf { it.isNotBlank() } ?: defaultModelValueForNode(nodeId)
	val modeValue = entry?.mode?.takeIf { it.isNotBlank() } ?: defaultModeForNode(nodeId)
	return configNode(
		nodeId = nodeId,
		title = entry?.name?.takeIf { it.isNotBlank() } ?: defaultTitleForNode(nodeId),
		role = entry?.role?.takeIf { it.isNotBlank() } ?: defaultRoleForNode(nodeId),
		task = entry?.task?.takeIf { it.isNotBlank() } ?: defaultTaskForNode(nodeId, currentGoal),
		modeLabel = displayModeLabel(modeValue),
		modeValue = modeValue,
		modelLabel = inheritedModel,
		modelValue = modelValue,
		selected = selected,
		removable = removable,
	)
}

private fun startConfigNode(currentGoal: String): OrchestrationConfigNodeRow =
	configNode(
		nodeId = "start",
		title = "START",
		role = "目标入口",
		task = currentGoal,
		modeLabel = "目标设置",
		modeValue = "GOAL",
		modelLabel = "current",
		modelValue = "goal:current",
		selected = true,
		removable = false,
	)

private fun endConfigNode(): OrchestrationConfigNodeRow =
	configNode(
		nodeId = "end",
		title = "END",
		role = "完成出口",
		task = "所有节点完成后，由主 Agent 确认输出并结束流程",
		modeLabel = "结束节点",
		modeValue = "END",
		modelLabel = "main-agent",
		modelValue = "end:main-agent-confirmed",
		selected = false,
		removable = false,
	)

private fun defaultConfigNodeFor(
	nodeId: String,
	currentGoal: String,
	inheritedModel: String,
): OrchestrationConfigNodeRow {
	val normalizedId = nodeId.trim().ifBlank { "node" }
	val modeValue = defaultModeForNode(normalizedId)
	return configNode(
		nodeId = normalizedId,
		title = defaultTitleForNode(normalizedId),
		role = defaultRoleForNode(normalizedId),
		task = explicitNodeTask(currentGoal, normalizedId) ?: defaultTaskForNode(normalizedId, currentGoal),
		modeLabel = displayModeLabel(modeValue),
		modeValue = modeValue,
		modelLabel = inheritedModel,
		selected = false,
		removable = true,
	)
}

private fun explicitNodeIdsFromGoal(goal: String): List<String> =
	ExplicitNodePattern.findAll(goal)
		.map { it.groupValues[1].trim().lowercase() }
		.filterNot { it == "start" || it == "end" }
		.distinct()
		.toList()

private fun explicitNodeTask(goal: String, nodeId: String): String? {
	val taskPattern = Regex(
		"""(?im)^\s*(?:[-*]|\d+[.)、]?)?\s*${Regex.escape(nodeId)}\s*(?:节点|node)\s*[:：,，\-]?\s*(.+)$""",
	)
	return taskPattern.find(goal)
		?.groupValues
		?.getOrNull(1)
		?.trim()
		?.takeIf { it.isNotBlank() }
}

private val ExplicitNodePattern = Regex(
	"""\b([A-Za-z][A-Za-z0-9_-]{1,31})\s*(?:节点|node)""",
	RegexOption.IGNORE_CASE,
)

private fun defaultTitleForNode(nodeId: String): String =
	when (nodeId.lowercase()) {
		"start" -> "START"
		"end" -> "END"
		"router" -> "汇总 router"
		else -> nodeId
	}

private fun defaultRoleForNode(nodeId: String): String =
	when (nodeId.lowercase()) {
		"start" -> "目标入口"
		"end" -> "完成出口"
		"explorer" -> "探索（只读）"
		"designer" -> "方案设计"
		"writer" -> "写入修改"
		"reviewer" -> "复核验收"
		"analyzer" -> "依赖分析"
		"tester" -> "跑测试"
		"router" -> "合并 / 路由"
		else -> "自定义节点"
	}

private fun defaultTaskForNode(nodeId: String, currentGoal: String): String =
	when (nodeId.lowercase()) {
		"start" -> currentGoal
		"end" -> "所有节点完成后，由主 Agent 确认输出并结束流程"
		"explorer" -> "读取相关文件并梳理当前状态"
		"designer" -> "设计实现方案并保留主要内容"
		"writer" -> "按方案修改工作区文件"
		"reviewer" -> "复核结果并确认是否满足目标"
		"analyzer" -> "分析模块依赖关系，定位依赖缺口与高耦合点"
		"tester" -> "运行相关测试并整理失败原因"
		"router" -> "汇总各节点结果，交给主 Agent 形成最终确认"
		else -> "补充这个节点的任务"
	}

private fun defaultModeForNode(nodeId: String): String =
	when (nodeId.lowercase()) {
		"start" -> "GOAL"
		"end" -> "END"
		"writer", "tester" -> "WORKSPACE_TOOL"
		else -> "READ_ONLY_TOOL"
	}

private fun defaultModelValueForNode(nodeId: String): String =
	when (nodeId.lowercase()) {
		"start" -> "goal:current"
		"end" -> "end:main-agent-confirmed"
		else -> "inherit"
	}

private fun displayModeLabel(modeValue: String): String =
	when (modeValue.uppercase()) {
		"GOAL" -> "目标设置"
		"END" -> "结束节点"
		else -> modeLabel(modeValue)
	}

private fun configNode(
	nodeId: String,
	title: String,
	role: String,
	task: String,
	modeLabel: String,
	modeValue: String = modeLabel,
	modelLabel: String,
	modelValue: String = "inherit",
	selected: Boolean = false,
	removable: Boolean,
): OrchestrationConfigNodeRow =
	OrchestrationConfigNodeRow(
		nodeId = nodeId,
		title = title,
		role = role,
		task = task,
		modelLabel = displayModelLabel(modelValue, modelLabel),
		modelValue = modelValue,
		modeLabel = modeLabel,
		modeValue = modeValue,
		selected = selected,
		removable = removable,
	)

private fun displayModelLabel(modelValue: String, modelLabel: String): String =
	when {
		modelValue.startsWith("goal:") -> "当前目标"
		modelValue.startsWith("end:") -> "主 Agent 确认输出"
		modelValue.startsWith("provider:") -> modelValue.removePrefix("provider:").replace(":", " / ")
		else -> "继承主 Agent · $modelLabel"
	}

private fun nodeSettingsModel(node: OrchestrationConfigNodeRow): OrchestrationNodeSettingsModel =
	OrchestrationNodeSettingsModel(
		nodeId = node.nodeId,
		title = "节点设置 · ${node.title}",
		task = node.task,
		modelLabel = node.modelLabel,
		modelValue = node.modelValue,
		removable = node.removable,
	)

fun nodeModelOptions(
	providerState: ProviderState,
	inheritedModelLabel: String,
): List<OrchestrationNodeModelOption> {
	val inheritedLabel = inheritedModelLabel
		.ifBlank { providerState.active.label }
		.ifBlank { "未选择模型" }
	val options = mutableListOf(
		OrchestrationNodeModelOption(
			providerId = null,
			modelId = null,
			label = "继承主 Agent · $inheritedLabel",
			modelValue = "inherit",
		),
	)
	providerState.providers
		.filter { it.enabled }
		.forEach { provider ->
			if (provider.models.isEmpty()) {
				val providerModel = provider.model?.takeIf { it.isNotBlank() }
				options += OrchestrationNodeModelOption(
					providerId = provider.id,
					modelId = null,
					label = listOf(provider.label, providerModel).filterNotNull().joinToString(" "),
					modelValue = "provider:${provider.id}",
				)
			} else {
				provider.models.forEach { model ->
					options += OrchestrationNodeModelOption(
						providerId = provider.id,
						modelId = model.id,
						label = "${provider.label} ${model.label}",
						modelValue = "provider:${provider.id}:${model.id}",
					)
				}
			}
		}
	return options.distinctBy { it.modelValue }
}

private fun nodeModelSelectionEnabled(nodeSettings: OrchestrationNodeSettingsModel): Boolean =
	nodeSettings.nodeId !in setOf("start", "end")

private fun selectedNodeModelLabel(
	nodeSettings: OrchestrationNodeSettingsModel,
	selectedModelValues: Map<String, String>,
	options: List<OrchestrationNodeModelOption>,
): String {
	val selectedValue = selectedModelValues[nodeSettings.nodeId] ?: nodeSettings.modelValue
	return options.firstOrNull { it.modelValue == selectedValue }?.label ?: nodeSettings.modelLabel
}

private fun buildOrchestrationConfigJson(
	detail: WorkUnitDetailModel,
	nodes: List<OrchestrationConfigNodeRow>,
	updatedNodeId: String? = null,
	updatedTask: String? = null,
	selectedModelValues: Map<String, String>,
): String {
	val entries = nodes.map { row ->
		WorkUnitConfigEntry(
			id = row.nodeId,
			name = row.title,
			role = row.role,
			task = if (row.nodeId == updatedNodeId && updatedTask != null) updatedTask.trim() else row.task,
			model = selectedModelValues[row.nodeId] ?: row.modelValue,
			mode = row.modeValue,
		)
	}
	return protocolJson.encodeToString(
		WorkUnitConfiguration(
			nodes = entries,
			members = detail.configuration?.members.orEmpty(),
		),
	)
}

private fun nodeRow(node: ThreadItem.OrchestrationNode): OrchestrationNodeRow =
	OrchestrationNodeRow(
		icon = statusIcon(node.status),
		title = node.displayName?.takeIf { it.isNotBlank() } ?: node.name,
		meta = listOfNotNull(
			modeLabel(node.mode),
			node.model?.takeIf { it.isNotBlank() },
			node.toolCallCount?.let { "工具 $it 次" },
			node.tokenEstimate?.let { "token $it" },
		).joinToString(" · "),
		detail = node.summary?.takeIf { it.isNotBlank() } ?: node.task.orEmpty().ifBlank { node.name },
		active = node.status.equals("running", ignoreCase = true),
	)

private fun statusIcon(status: String): String =
	when (status.lowercase()) {
		"completed" -> "OK"
		"running" -> "RUN"
		"failed" -> "!"
		else -> "WAIT"
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

private fun runtimeRemoveActionLabel(status: String): String? =
	if (status.lowercase() in setOf("completed", "failed", "canceled")) "移除" else null

private fun topologyLabel(topology: String): String =
	when (topology.lowercase()) {
		"sequential" -> "顺序"
		"parallel" -> "并行"
		"routing" -> "路由"
		else -> topology
	}

private fun modeLabel(mode: String): String =
	when (mode.uppercase()) {
		"READ_ONLY_TOOL" -> "只读工具"
		"WORKSPACE_TOOL" -> "工作区工具"
		else -> mode
	}

private fun approvalLabel(item: ThreadItem.Orchestration): String =
	if (item.approved == true && item.frozen == true) "已审批并冻结" else "未冻结"

private fun compactFlowSummary(summary: String?): String? {
	val compact = summary.orEmpty()
		.lineSequence()
		.map { it.trim() }
		.filter { it.isNotBlank() }
		.map { it.trimStart('#').trim().replace("`", "") }
		.filter { it.isNotBlank() }
		.take(2)
		.joinToString(" · ")
		.replace(Regex("\\s+"), " ")
		.trim()
	if (compact.isBlank()) {
		return null
	}
	return if (compact.length <= FlowSummaryMaxChars) {
		compact
	} else {
		compact.take(FlowSummaryMaxChars - 3).trimEnd() + "..."
	}
}
