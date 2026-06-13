package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.flowcanvas.FlowCanvas
import com.wzx.babiq.desktop.flowcanvas.FlowCanvasMode
import com.wzx.babiq.desktop.flowcanvas.FlowDropTarget
import com.wzx.babiq.desktop.flowcanvas.FlowGraph
import com.wzx.babiq.desktop.flowcanvas.FlowGraphHistory
import com.wzx.babiq.desktop.flowcanvas.FlowInsertKind
import com.wzx.babiq.desktop.flowcanvas.FlowNode
import com.wzx.babiq.desktop.flowcanvas.FlowNodeMode
import com.wzx.babiq.desktop.flowcanvas.FlowNodeStatus
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
	val configTopology: String = "sequential",
	val configNodes: List<OrchestrationConfigNodeRow> = emptyList(),
	val selectedNodeSettings: OrchestrationNodeSettingsModel? = null,
	val addNodeActions: List<OrchestrationAddNodeAction> = emptyList(),
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

data class OrchestrationAddNodeAction(
	val label: String,
	val mode: OrchestrationDraftAddMode,
)

enum class OrchestrationDraftAddMode {
	Serial,
	Parallel,
	Routing,
}

data class OrchestrationDraftNodeUpdate(
	val nodes: List<OrchestrationConfigNodeRow>,
	val topology: String,
)

fun applyOrchestrationGraphEdit(history: FlowGraphHistory, next: FlowGraph): FlowGraphHistory =
	if (history.current == next) history else history.apply(next)

fun undoOrchestrationGraphEdit(history: FlowGraphHistory): FlowGraphHistory = history.undo()

fun redoOrchestrationGraphEdit(history: FlowGraphHistory): FlowGraphHistory = history.redo()

fun buildOrchestrationSectionModel(
	state: OrchestrationUiState,
	modelLabel: String = "model not selected",
): OrchestrationSectionModel {
	val item = state.current
	if (item != null) {
		return OrchestrationSectionModel(
			visible = true,
			title = "Flow orchestration - ${item.title}",
			subtitle = "${topologyLabel(item.topology)} / ${statusLabel(item.status)} / ${approvalLabel(item)}",
			nodes = item.nodes.map(::nodeRow),
			summaryPreview = compactFlowSummary(item.summary),
			config = null,
			removeActionLabel = runtimeRemoveActionLabel(item.status),
		)
	}
	val config = state.configuringWorkUnit ?: return OrchestrationSectionModel(false, "", "", emptyList())
	val detail = workUnitDetailModel(config, modelLabel)
	val graph = flowGraphFromWorkUnitDetail(detail)
	val rows = configRowsFromGraph(graph, detail)
	val selected = rows.firstOrNull { it.removable } ?: rows.firstOrNull()
	return OrchestrationSectionModel(
		visible = true,
		title = "Flow detail - ${config.name}",
		subtitle = "${statusLabel(config.status)} / ${config.goals.size} goals / waiting for manual start",
		nodes = emptyList(),
		config = detail,
		configTopology = graph.root.topology.wireValue,
		configNodes = rows,
		selectedNodeSettings = selected?.let(::nodeSettingsModel),
		addNodeActions = defaultAddNodeActions(),
		addNodeActionLabel = "add node",
		removeActionLabel = detail.removeActionLabel,
		editModeTitle = "Flow canvas",
	)
}

@Composable
fun OrchestrationSection(
	state: OrchestrationUiState,
	modelLabel: String = "model not selected",
	providerState: ProviderState = ProviderState(),
	onStartWorkUnit: (String) -> Unit = {},
	onRemoveWorkUnit: (String) -> Unit = {},
	onDismissOrchestration: () -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onUpdateWorkUnitConfig: (String, String, String?) -> Unit = { _, _, _ -> },
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
			verticalArrangement = Arrangement.spacedBy(10.dp),
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
							if (workUnitId != null) onRemoveWorkUnit(workUnitId) else onDismissOrchestration()
						},
					) {
						Text(label)
					}
				}
			}
			model.config?.let { config ->
				OrchestrationConfigPanel(
					detail = config,
					providerState = providerState,
					onStart = onStartWorkUnit,
					onUpdateGoal = onUpdateWorkUnitGoal,
					onUpdateConfig = onUpdateWorkUnitConfig,
				)
			}
			if (model.config == null && state.current != null) {
				RuntimeFlowCanvas(item = state.current)
			}
			model.nodes.forEach { row -> OrchestrationNodeRowView(row) }
			model.summaryPreview?.let { preview -> FlowSummaryPreview(preview) }
		}
	}
}

@Composable
private fun OrchestrationConfigPanel(
	detail: WorkUnitDetailModel,
	providerState: ProviderState,
	onStart: (String) -> Unit,
	onUpdateGoal: (String, String, String) -> Unit,
	onUpdateConfig: (String, String, String?) -> Unit,
) {
	var history by remember(detail.workUnitId, detail.configJson, detail.structureJson) {
		mutableStateOf(FlowGraphHistory(flowGraphFromWorkUnitDetail(detail)))
	}
	val graph = history.current
	var draftGoal by remember(detail.editableGoalId, detail.editableGoalText) {
		mutableStateOf(detail.editableGoalText ?: "")
	}
	val selectedNode = graph.selectedNode
	var draftTask by remember(detail.workUnitId, selectedNode?.id, selectedNode?.task) {
		mutableStateOf(selectedNode?.task.orEmpty())
	}
	var selectedModelValue by remember(detail.workUnitId, selectedNode?.id, selectedNode?.modelValue) {
		mutableStateOf(selectedNode?.modelValue ?: "inherit")
	}
	val modelOptions = nodeModelOptions(providerState, detail.modelLabel)
	val selectedModelLabel = modelOptions.firstOrNull { it.modelValue == selectedModelValue }?.label
		?: selectedNode?.modelLabel
		?: "inherit"
	val nodeChanged = selectedNode != null &&
		draftTask.isNotBlank() &&
		(draftTask != selectedNode.task || selectedModelValue != selectedNode.modelValue)
	val persistGraph: (FlowGraph) -> Unit = { next ->
		onUpdateConfig(detail.workUnitId, buildFlowConfigJson(detail, next), buildFlowStructureJson(next))
	}
	fun applyGraphEdit(next: FlowGraph) {
		val nextHistory = applyOrchestrationGraphEdit(history, next)
		history = nextHistory
		persistGraph(nextHistory.current)
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.onPreviewKeyEvent { event ->
				if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.key != Key.Z) {
					return@onPreviewKeyEvent false
				}
				val nextHistory = if (event.isShiftPressed) {
					redoOrchestrationGraphEdit(history)
				} else {
					undoOrchestrationGraphEdit(history)
				}
				if (nextHistory == history) {
					false
				} else {
					history = nextHistory
					persistGraph(nextHistory.current)
					true
				}
			},
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		detail.editableGoalId?.let { goalId ->
			OutlinedTextField(
				value = draftGoal,
				onValueChange = { draftGoal = it },
				label = { Text("Current goal") },
				modifier = Modifier.fillMaxWidth(),
				minLines = 2,
				maxLines = 4,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				Button(
					onClick = { onUpdateGoal(detail.workUnitId, goalId, draftGoal.trim()) },
					enabled = draftGoal.isNotBlank() && draftGoal != (detail.editableGoalText ?: ""),
				) {
					Text("Save goal")
				}
				OutlinedButton(
					onClick = {
						val node = newFlowNodeForGraph(graph, detail.modelLabel)
						val next = graph.insertSerial(graph.flattenNodeIds().lastOrNull(), node)
						applyGraphEdit(next)
					},
				) {
					Text("+ node")
				}
				detail.startActionLabel?.let { label ->
					Button(onClick = { onStart(detail.workUnitId) }) { Text(label) }
				}
			}
		}
		CanvasFrame(
			graph = graph,
			onSelect = { history = history.copy(current = graph.copy(selectedNodeId = it)) },
			onInsert = { anchor, kind ->
				val node = newFlowNodeForGraph(graph, detail.modelLabel)
				val next = when (kind) {
					FlowInsertKind.Serial -> graph.insertSerial(anchor, node)
					FlowInsertKind.Parallel -> graph.insertParallel(anchor ?: graph.flattenNodeIds().lastOrNull(), node)
					FlowInsertKind.Routing -> graph.insertRouting(anchor ?: graph.flattenNodeIds().lastOrNull(), node)
				}
				applyGraphEdit(next)
			},
			onMove = { nodeId, target ->
				val next = graph.moveEntry(nodeId, target)
				applyGraphEdit(next)
			},
		)
		selectedNode?.let { node ->
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
					.padding(10.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
					Text("Node settings - ${node.title}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
					TextButton(
						onClick = {
							val next = graph.removeNode(node.id)
							applyGraphEdit(next)
						},
						enabled = node.removable,
					) {
						Text("Delete")
					}
				}
				OutlinedTextField(
					value = draftTask,
					onValueChange = { draftTask = it },
					label = { Text("Task") },
					modifier = Modifier.fillMaxWidth(),
					minLines = 3,
					maxLines = 6,
				)
				NodeModelSelector(
					selectedLabel = selectedModelLabel,
					options = modelOptions,
					enabled = modelOptions.isNotEmpty(),
					onSelect = { selectedModelValue = it.modelValue },
				)
				Button(
					onClick = {
						val updated = node.copy(
							task = draftTask.trim(),
							modelValue = selectedModelValue,
							modelLabel = selectedModelLabel,
						)
						val next = graph.replaceNode(updated)
						applyGraphEdit(next)
					},
					enabled = nodeChanged,
				) {
					Text("Save node")
				}
			}
		}
		Text("Goal queue", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		if (detail.goals.isEmpty()) {
			Text("No goals", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		} else {
			detail.goals.forEach { goal -> Text(goal.label, style = MaterialTheme.typography.bodySmall) }
		}
	}
}

@Composable
private fun CanvasFrame(
	graph: FlowGraph,
	onSelect: (String) -> Unit,
	onInsert: (String?, FlowInsertKind) -> Unit,
	onMove: (String, FlowDropTarget) -> Unit = { _, _ -> },
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(360.dp)
			.background(BaBiQColors.Background, RoundedCornerShape(10.dp))
			.border(1.dp, BaBiQColors.Border, RoundedCornerShape(10.dp))
			.padding(10.dp)
			.horizontalScroll(rememberScrollState())
			.verticalScroll(rememberScrollState()),
	) {
		FlowCanvas(
			graph = graph,
			mode = FlowCanvasMode.Edit,
			onSelectNode = onSelect,
			onInsert = onInsert,
			onMove = onMove,
		)
	}
}

@Composable
private fun RuntimeFlowCanvas(item: ThreadItem.Orchestration) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(320.dp)
			.background(BaBiQColors.Background, RoundedCornerShape(10.dp))
			.border(1.dp, BaBiQColors.Border, RoundedCornerShape(10.dp))
			.padding(10.dp)
			.horizontalScroll(rememberScrollState())
			.verticalScroll(rememberScrollState()),
	) {
		FlowCanvas(
			graph = flowGraphFromOrchestrationItem(item),
			mode = FlowCanvasMode.Playback,
		)
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
		OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty()) {
			Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

fun addOrchestrationDraftNode(
	nodes: List<OrchestrationConfigNodeRow>,
	inheritedModelLabel: String,
): List<OrchestrationConfigNodeRow> =
	addOrchestrationDraftNodeWithTopology(
		nodes = nodes,
		inheritedModelLabel = inheritedModelLabel,
		currentTopology = "sequential",
		mode = OrchestrationDraftAddMode.Serial,
	).nodes

fun addOrchestrationDraftNodeWithTopology(
	nodes: List<OrchestrationConfigNodeRow>,
	inheritedModelLabel: String,
	currentTopology: String,
	mode: OrchestrationDraftAddMode,
): OrchestrationDraftNodeUpdate {
	val existingIds = nodes.map { it.nodeId }.toSet()
	val nodeId = generateSequence(1) { it + 1 }
		.map { "node_$it" }
		.first { it !in existingIds }
	val newNode = configNode(
		nodeId = nodeId,
		title = nodeId,
		role = "custom node",
		task = "Fill in this node task",
		modeLabel = modeLabel("READ_ONLY_TOOL"),
		modeValue = "READ_ONLY_TOOL",
		modelLabel = inheritedModelLabel.ifBlank { "model not selected" },
		selected = true,
		removable = true,
	)
	val cleared = nodes.map { it.copy(selected = false) }
	val endIndex = cleared.indexOfFirst { it.nodeId == "end" }
	val updatedNodes = if (endIndex < 0) cleared + newNode else cleared.take(endIndex) + newNode + cleared.drop(endIndex)
	val updatedTopology = when (mode) {
		OrchestrationDraftAddMode.Serial -> currentTopology.ifBlank { "sequential" }
		OrchestrationDraftAddMode.Parallel -> "parallel"
		OrchestrationDraftAddMode.Routing -> "routing"
	}
	return OrchestrationDraftNodeUpdate(updatedNodes, updatedTopology)
}

fun nodeModelOptions(
	providerState: ProviderState,
	inheritedModelLabel: String,
): List<OrchestrationNodeModelOption> {
	val inheritedLabel = inheritedModelLabel
		.ifBlank { providerState.active.label }
		.ifBlank { "model not selected" }
	val options = mutableListOf(
		OrchestrationNodeModelOption(null, null, "inherit main Agent / $inheritedLabel", "inherit"),
	)
	providerState.providers
		.filter { it.enabled }
		.forEach { provider ->
			if (provider.models.isEmpty()) {
				options += OrchestrationNodeModelOption(
					providerId = provider.id,
					modelId = null,
					label = listOf(provider.label, provider.model?.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" "),
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

private fun defaultAddNodeActions(): List<OrchestrationAddNodeAction> =
	listOf(
		OrchestrationAddNodeAction("serial node", OrchestrationDraftAddMode.Serial),
		OrchestrationAddNodeAction("parallel node", OrchestrationDraftAddMode.Parallel),
		OrchestrationAddNodeAction("routing branch", OrchestrationDraftAddMode.Routing),
	)

private fun configRowsFromGraph(graph: FlowGraph, detail: WorkUnitDetailModel): List<OrchestrationConfigNodeRow> {
	val currentGoal = currentConfigGoal(detail)
	return listOf(startConfigNode(currentGoal)) +
		graph.nodes.mapIndexed { index, node -> node.toConfigRow(selected = index == 0) } +
		listOf(endConfigNode())
}

private fun FlowNode.toConfigRow(selected: Boolean): OrchestrationConfigNodeRow =
	configNode(
		nodeId = id,
		title = title,
		role = role,
		task = task,
		modeLabel = modeLabel(mode.wireValue),
		modeValue = mode.wireValue,
		modelLabel = modelLabel,
		modelValue = modelValue,
		selected = selected,
		removable = removable,
	)

private fun currentConfigGoal(detail: WorkUnitDetailModel): String =
	detail.editableGoalText
		?: detail.goals.lastOrNull()?.label?.substringAfter(" - ")?.trim()
		?: detail.title

private fun startConfigNode(currentGoal: String): OrchestrationConfigNodeRow =
	configNode(
		nodeId = "start",
		title = "START",
		role = "goal entry",
		task = currentGoal,
		modeLabel = "goal",
		modeValue = "GOAL",
		modelLabel = "current goal",
		modelValue = "goal:current",
		selected = false,
		removable = false,
	)

private fun endConfigNode(): OrchestrationConfigNodeRow =
	configNode(
		nodeId = "end",
		title = "END",
		role = "flow exit",
		task = "Main Agent confirms final output after all nodes finish.",
		modeLabel = "end",
		modeValue = "END",
		modelLabel = "main Agent",
		modelValue = "end:main-agent-confirmed",
		selected = false,
		removable = false,
	)

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

private fun nodeSettingsModel(node: OrchestrationConfigNodeRow): OrchestrationNodeSettingsModel =
	OrchestrationNodeSettingsModel(
		nodeId = node.nodeId,
		title = "Node settings - ${node.title}",
		task = node.task,
		modelLabel = node.modelLabel,
		modelValue = node.modelValue,
		removable = node.removable,
	)

@Suppress("unused")
private fun buildOrchestrationConfigJson(
	detail: WorkUnitDetailModel,
	topology: String,
	nodes: List<OrchestrationConfigNodeRow>,
	updatedNodeId: String? = null,
	updatedTask: String? = null,
	selectedModelValues: Map<String, String>,
): String {
	val entries = nodes
		.filterNot { it.nodeId.equals("start", ignoreCase = true) || it.nodeId.equals("end", ignoreCase = true) }
		.map { row ->
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
			topology = topology.ifBlank { "sequential" },
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
			node.toolCallCount?.let { "tools $it" },
			node.tokenEstimate?.let { "token $it" },
		).joinToString(" / "),
		detail = node.summary?.takeIf { it.isNotBlank() } ?: node.task.orEmpty().ifBlank { node.name },
		active = node.status.equals("running", ignoreCase = true),
	)

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
		Text("${row.icon} ${row.title}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
		Text(row.meta, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(row.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
		Text("Flow summary", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 3)
	}
}

private fun statusIcon(status: String): String =
	when (status.lowercase()) {
		"completed" -> "OK"
		"running" -> "RUN"
		"failed" -> "ERR"
		else -> "WAIT"
	}

private fun statusLabel(status: String): String =
	when (status.lowercase()) {
		"idle", "pending", "waiting_config" -> "waiting config"
		"running" -> "running"
		"completed" -> "completed"
		"failed" -> "failed"
		"canceled" -> "canceled"
		else -> status
	}

private fun runtimeRemoveActionLabel(status: String): String? =
	if (status.lowercase() in setOf("completed", "failed", "canceled")) "remove" else null

private fun topologyLabel(topology: String): String =
	when (topology.lowercase()) {
		"sequential" -> "sequential"
		"parallel" -> "parallel"
		"routing" -> "routing"
		else -> topology
	}

private fun modeLabel(mode: String): String =
	when (mode.uppercase()) {
		"READ_ONLY_TOOL" -> "read only"
		"WORKSPACE_TOOL" -> "workspace"
		"GOAL" -> "goal"
		"END" -> "end"
		else -> mode
	}

private fun approvalLabel(item: ThreadItem.Orchestration): String =
	if (item.approved == true && item.frozen == true) "approved and frozen" else "not frozen"

private fun displayModelLabel(modelValue: String, modelLabel: String): String =
	when {
		modelValue.startsWith("goal:") -> "current goal"
		modelValue.startsWith("end:") -> "main Agent confirms output"
		modelValue.startsWith("provider:") -> modelValue.removePrefix("provider:").replace(":", " / ")
		else -> "inherit main Agent / $modelLabel"
	}

private fun compactFlowSummary(summary: String?): String? {
	val compact = summary.orEmpty()
		.lineSequence()
		.map { it.trim() }
		.filter { it.isNotBlank() }
		.map { it.trimStart('#').trim().replace("`", "") }
		.filter { it.isNotBlank() }
		.take(2)
		.joinToString(" / ")
		.replace(Regex("\\s+"), " ")
		.trim()
	if (compact.isBlank()) return null
	return if (compact.length <= FlowSummaryMaxChars) compact else compact.take(FlowSummaryMaxChars - 3).trimEnd() + "..."
}
