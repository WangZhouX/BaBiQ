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
		editModeTitle = "编排 · 编辑模式",
	)
}

@Composable
fun OrchestrationSection(
	state: OrchestrationUiState,
	modelLabel: String = "未选择模型",
	providerState: ProviderState = ProviderState(),
	onStartWorkUnit: (String) -> Unit = {},
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
			Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
			Text(model.subtitle, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
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
	var selectedNodeId by remember(detail.workUnitId, nodes) {
		mutableStateOf(defaultSettings?.nodeId ?: nodes.firstOrNull()?.nodeId)
	}
	val selectedRow = nodes.firstOrNull { it.nodeId == selectedNodeId } ?: nodes.firstOrNull()
	val settings = selectedRow?.let(::nodeSettingsModel)
	var draftTask by remember(detail.workUnitId, settings?.nodeId, settings?.task) {
		mutableStateOf(settings?.task.orEmpty())
	}
	var selectedModelValues by remember(detail.workUnitId, nodes) {
		mutableStateOf(nodes.associate { it.nodeId to it.modelValue })
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
			nodes = nodes,
			selectedNodeId = selectedNodeId,
			onSelect = { selectedNodeId = it },
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
							if (nodeSettings.nodeId == "start" && draftTask != nodeSettings.task) {
								detail.editableGoalId?.let { goalId ->
									onUpdateGoal(detail.workUnitId, goalId, draftTask)
								}
							}
							onUpdateConfig(
								detail.workUnitId,
								buildOrchestrationConfigJson(
									detail = detail,
									nodes = nodes,
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
					OutlinedButton(onClick = {}, enabled = false) { Text("+ $label") }
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
	val byId = nodes.associateBy { it.nodeId }
	Column(
		modifier = Modifier.fillMaxWidth(),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(0.dp),
	) {
		byId["start"]?.let { node ->
			OrchestrationTerminalNode(
				node = node.copy(selected = node.nodeId == selectedNodeId),
				onSelect = { onSelect(node.nodeId) },
			)
		}
		VerticalConnector()
		byId["explorer"]?.let { node ->
			OrchestrationConfigNodeCard(
				node = node.copy(selected = node.nodeId == selectedNodeId),
				onSelect = { onSelect(node.nodeId) },
			)
		}
		BranchConnector()
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			listOf("analyzer", "tester").forEach { nodeId ->
				byId[nodeId]?.let { node ->
					OrchestrationConfigNodeCard(
						node = node.copy(selected = node.nodeId == selectedNodeId),
						onSelect = { onSelect(node.nodeId) },
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
		BranchConnector()
		byId["router"]?.let { node ->
			OrchestrationConfigNodeCard(
				node = node.copy(selected = node.nodeId == selectedNodeId),
				onSelect = { onSelect(node.nodeId) },
			)
		}
		VerticalConnector()
		byId["end"]?.let { node ->
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
private fun BranchConnector() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 36.dp)
			.height(22.dp),
	) {
		Box(
			modifier = Modifier
				.align(Alignment.Center)
				.fillMaxWidth()
				.height(1.dp)
				.background(BaBiQColors.Border),
		)
		Box(
			modifier = Modifier
				.align(Alignment.Center)
				.size(width = 1.dp, height = 22.dp)
				.background(BaBiQColors.Border),
		)
	}
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
	val currentGoal = detail.editableGoalText
		?: detail.goals.lastOrNull()?.label?.substringAfter("·")?.trim()
		?: detail.title
	val overrides = detail.configuration?.nodes.orEmpty().associateBy { it.id }
	return listOf(
		configNode(
			nodeId = "start",
			title = "START",
			role = "目标入口",
			task = currentGoal,
			modeLabel = "目标设置",
			modelLabel = "current",
			modelValue = "goal:current",
			selected = true,
			removable = false,
		),
		configNode(
			nodeId = "explorer",
			title = "explorer",
			role = "探查（只读）",
			task = "读取相关文件并梳理当前状态",
			modeLabel = "只读工具",
			modelLabel = inheritedModel,
			removable = false,
		),
		configNode(
			nodeId = "analyzer",
			title = "analyzer",
			role = "依赖分析",
			task = "分析模块依赖关系，定位循环依赖与高耦合点",
			modeLabel = "只读工具",
			modelLabel = inheritedModel,
			removable = true,
		),
		configNode(
			nodeId = "tester",
			title = "tester",
			role = "跑测试",
			task = "运行相关测试并整理失败原因",
			modeLabel = "工作区工具",
			modelLabel = inheritedModel,
			removable = true,
		),
		configNode(
			nodeId = "router",
			title = "汇总 router",
			role = "合并 / 路由",
			task = "汇总各节点结果，交给主 Agent 形成最终确认",
			modeLabel = "汇总节点",
			modelLabel = inheritedModel,
			removable = true,
		),
		configNode(
			nodeId = "end",
			title = "END",
			role = "完成出口",
			task = "所有节点完成后，由主 Agent 确认输出并结束流程",
			modeLabel = "结束节点",
			modelLabel = "main-agent",
			modelValue = "end:main-agent-confirmed",
			removable = false,
		),
	).map { row ->
		val override = overrides[row.nodeId] ?: return@map row
		row.copy(
			title = override.name?.takeIf { it.isNotBlank() } ?: row.title,
			role = override.role?.takeIf { it.isNotBlank() } ?: row.role,
			task = override.task?.takeIf { it.isNotBlank() } ?: row.task,
			modelValue = override.model?.takeIf { it.isNotBlank() } ?: row.modelValue,
			modelLabel = displayModelLabel(
				override.model?.takeIf { it.isNotBlank() } ?: row.modelValue,
				inheritedModel,
			),
			modeLabel = override.mode?.takeIf { it.isNotBlank() } ?: row.modeLabel,
		)
	}
}

private fun configNode(
	nodeId: String,
	title: String,
	role: String,
	task: String,
	modeLabel: String,
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
	updatedNodeId: String,
	updatedTask: String,
	selectedModelValues: Map<String, String>,
): String {
	val entries = nodes.map { row ->
		WorkUnitConfigEntry(
			id = row.nodeId,
			name = row.title,
			role = row.role,
			task = if (row.nodeId == updatedNodeId) updatedTask.trim() else row.task,
			model = selectedModelValues[row.nodeId] ?: row.modelValue,
			mode = row.modeLabel,
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
