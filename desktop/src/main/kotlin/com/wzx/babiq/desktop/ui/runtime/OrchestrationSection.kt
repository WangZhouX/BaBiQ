package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.wzx.babiq.desktop.flowcanvas.FlowInsertTarget
import com.wzx.babiq.desktop.flowcanvas.FlowNode
import com.wzx.babiq.desktop.flowcanvas.FlowNodeMode
import com.wzx.babiq.desktop.flowcanvas.FlowNodeStatus
import com.wzx.babiq.desktop.flowcanvas.flowInsertKindLabel
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitConfigEntry
import com.wzx.babiq.desktop.protocol.WorkUnitConfiguration
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.OrchestrationUiState
import com.wzx.babiq.desktop.state.ProviderState
import com.wzx.babiq.desktop.state.WorkUnitConfigConflict
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import kotlinx.serialization.encodeToString

private const val FlowSummaryMaxChars = 120

data class OrchestrationSectionModel(
	val visible: Boolean,
	val title: String,
	val subtitle: String,
	val nodes: List<OrchestrationNodeRow>,
	val summaryPreview: String? = null,
	val runtime: ThreadItem.Orchestration? = null,
	val config: WorkUnitDetailModel? = null,
	val showRuntimeCanvas: Boolean = runtime != null && config == null,
	val configTopology: String = "sequential",
	val configNodes: List<OrchestrationConfigNodeRow> = emptyList(),
	val selectedNodeSettings: OrchestrationNodeSettingsModel? = null,
	val addNodeActions: List<OrchestrationAddNodeAction> = emptyList(),
	val addNodeActionLabel: String? = null,
	val removeActionLabel: String? = null,
	val backActionLabel: String? = null,
	val startActionLabel: String? = null,
	val editModeTitle: String? = null,
	val configConflict: WorkUnitConfigConflict? = null,
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
	val nodeTitle: String,
	val title: String,
	val task: String,
	val modelLabel: String,
	val modelValue: String,
	val modeLabel: String,
	val modeValue: String,
	val removable: Boolean,
)

data class OrchestrationNodeModelOption(
	val providerId: String?,
	val modelId: String?,
	val label: String,
	val modelValue: String,
)

data class OrchestrationNodeModeOption(
	val value: String,
	val label: String,
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

fun toolbarSerialInsertTarget(graph: FlowGraph): FlowInsertTarget =
	graph.selectedNode?.let { FlowInsertTarget.AfterNode(it.id) } ?: FlowInsertTarget.Append

fun applyOrchestrationNodeSettings(
	graph: FlowGraph,
	nodeId: String,
	title: String,
	task: String,
	modeValue: String,
	modelValue: String,
	modelLabel: String,
): FlowGraph {
	val node = graph.nodeMap[nodeId] ?: return graph
	return graph.replaceNode(
		node.copy(
			title = title.trim().ifBlank { node.id },
			task = task.trim(),
			mode = FlowNodeMode.from(modeValue),
			modelValue = modelValue,
			modelLabel = modelLabel,
		),
	)
}

fun buildOrchestrationSectionModel(
	state: OrchestrationUiState,
	modelLabel: String = "未选择模型",
): OrchestrationSectionModel {
	val config = state.configuringWorkUnit
	if (config != null) {
		val detail = workUnitDetailModel(config, modelLabel)
		val graph = flowGraphFromWorkUnitDetail(detail)
		val rows = configRowsFromGraph(graph, detail)
		val selected = rows.firstOrNull { it.removable } ?: rows.firstOrNull()
		return OrchestrationSectionModel(
			visible = true,
			title = "编排详情 · ${config.name}",
			subtitle = "${statusLabel(config.status)} / 等待手动启动",
			nodes = emptyList(),
			summaryPreview = null,
			runtime = state.current,
			config = detail,
			configTopology = graph.root.topology.wireValue,
			configNodes = rows,
			selectedNodeSettings = selected?.let(::nodeSettingsModel),
			addNodeActions = defaultAddNodeActions(),
			addNodeActionLabel = "添加节点",
			removeActionLabel = detail.removeActionLabel,
			backActionLabel = "返回列表",
			startActionLabel = detail.startActionLabel,
			editModeTitle = "编排画布",
			configConflict = state.configConflict?.takeIf { it.workUnitId == config.workUnitId },
		)
	}
	if (!state.visible) {
		return OrchestrationSectionModel(false, "", "", emptyList())
	}
	val item = state.current
	if (item != null) {
		return OrchestrationSectionModel(
			visible = true,
			title = "流程编排 · ${item.title}",
			subtitle = "${topologyLabel(item.topology)} / ${statusLabel(item.status)} / ${approvalLabel(item)}",
			nodes = item.nodes.map(::nodeRow),
			summaryPreview = compactFlowSummary(item.summary),
			runtime = item,
			config = null,
		)
	}
	return OrchestrationSectionModel(false, "", "", emptyList())
}

@Composable
fun OrchestrationSection(
	state: OrchestrationUiState,
	modelLabel: String = "未选择模型",
	providerState: ProviderState = ProviderState(),
	onStartWorkUnit: (String) -> Unit = {},
	onRemoveWorkUnit: (String) -> Unit = {},
	onDismissOrchestration: () -> Unit = {},
	onBackToList: () -> Unit = {},
	onUpdateWorkUnitGoal: (String, String, String) -> Unit = { _, _, _ -> },
	onRenameWorkUnit: (String, String) -> Unit = { _, _ -> },
	onUpdateWorkUnitConfig: (String, String, String?) -> Unit = { _, _, _ -> },
	onMarkWorkUnitConfigDraftDirty: (String) -> Unit = {},
	onLoadLatestWorkUnitConfig: (String) -> Unit = {},
	onKeepWorkUnitConfigDraft: (String) -> Unit = {},
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
				model.backActionLabel?.let { label ->
					TextButton(onClick = onBackToList) { Text(label) }
				}
				model.startActionLabel?.let { label ->
					model.config?.workUnitId?.let { workUnitId ->
						Button(onClick = { onStartWorkUnit(workUnitId) }) { Text(label) }
					}
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
					onUpdateGoal = onUpdateWorkUnitGoal,
					onRenameWorkUnit = onRenameWorkUnit,
					onUpdateConfig = onUpdateWorkUnitConfig,
					configConflict = model.configConflict,
					onConfigDraftChanged = onMarkWorkUnitConfigDraftDirty,
					onLoadLatestConfig = onLoadLatestWorkUnitConfig,
					onKeepLocalDraft = onKeepWorkUnitConfigDraft,
				)
			}
			if (model.showRuntimeCanvas) model.runtime?.let { runtime ->
				RuntimeFlowCanvas(item = runtime)
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
	onUpdateGoal: (String, String, String) -> Unit,
	onRenameWorkUnit: (String, String) -> Unit,
	onUpdateConfig: (String, String, String?) -> Unit,
	configConflict: WorkUnitConfigConflict?,
	onConfigDraftChanged: (String) -> Unit,
	onLoadLatestConfig: (String) -> Unit,
	onKeepLocalDraft: (String) -> Unit,
) {
	var history by remember(detail.workUnitId, detail.configJson, detail.structureJson) {
		mutableStateOf(FlowGraphHistory(flowGraphFromWorkUnitDetail(detail)))
	}
	val graph = history.current
	var draftGoal by remember(detail.editableGoalId, detail.editableGoalText) {
		mutableStateOf(detail.editableGoalText ?: "")
	}
	var draftWorkUnitName by remember(detail.workUnitId, detail.name) {
		mutableStateOf(detail.name)
	}
	val selectedNode = graph.selectedNode
	var draftTitle by remember(detail.workUnitId, selectedNode?.id, selectedNode?.title) {
		mutableStateOf(selectedNode?.title.orEmpty())
	}
	var draftTask by remember(detail.workUnitId, selectedNode?.id, selectedNode?.task) {
		mutableStateOf(selectedNode?.task.orEmpty())
	}
	var selectedModeValue by remember(detail.workUnitId, selectedNode?.id, selectedNode?.mode) {
		mutableStateOf(selectedNode?.mode?.wireValue ?: FlowNodeMode.ReadOnlyTool.wireValue)
	}
	var selectedModelValue by remember(detail.workUnitId, selectedNode?.id, selectedNode?.modelValue) {
		mutableStateOf(selectedNode?.modelValue ?: "inherit")
	}
	val modelOptions = nodeModelOptions(providerState, detail.modelLabel)
	val selectedModelLabel = modelOptions.firstOrNull { it.modelValue == selectedModelValue }?.label
		?: selectedNode?.modelLabel
		?: "inherit"
	val nodeChanged = selectedNode != null &&
		draftTitle.trim().isNotBlank() &&
		draftTask.isNotBlank() &&
		(
			draftTitle.trim() != selectedNode.title ||
				draftTask != selectedNode.task ||
				selectedModeValue != selectedNode.mode.wireValue ||
				selectedModelValue != selectedNode.modelValue
		)
	val persistGraph: (FlowGraph) -> Unit = { next ->
		onUpdateConfig(detail.workUnitId, buildFlowConfigJson(detail, next), buildFlowStructureJson(next))
	}
	fun applyGraphEdit(next: FlowGraph) {
		val nextHistory = applyOrchestrationGraphEdit(history, next)
		history = nextHistory
		onConfigDraftChanged(detail.workUnitId)
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
					onConfigDraftChanged(detail.workUnitId)
					persistGraph(nextHistory.current)
					true
				}
		},
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		OutlinedTextField(
			value = draftWorkUnitName,
			onValueChange = { draftWorkUnitName = it },
			label = { Text("编排名称") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
		)
		Button(
			onClick = { onRenameWorkUnit(detail.workUnitId, draftWorkUnitName.trim()) },
			enabled = draftWorkUnitName.trim().isNotBlank() && draftWorkUnitName.trim() != detail.name,
		) {
			Text("保存名称")
		}
		configConflict?.let { conflict ->
			WorkUnitConfigConflictBanner(
				conflict = conflict,
				onLoadLatest = { onLoadLatestConfig(conflict.workUnitId) },
				onKeepDraft = { onKeepLocalDraft(conflict.workUnitId) },
			)
		}
		detail.editableGoalId?.let { goalId ->
			OutlinedTextField(
				value = draftGoal,
				onValueChange = { draftGoal = it },
				label = { Text("当前目标") },
				modifier = Modifier.fillMaxWidth(),
				minLines = 2,
				maxLines = 4,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				Button(
					onClick = { onUpdateGoal(detail.workUnitId, goalId, draftGoal.trim()) },
					enabled = draftGoal.isNotBlank() && draftGoal != (detail.editableGoalText ?: ""),
				) {
					Text("保存目标")
				}
				OutlinedButton(
					onClick = {
						val node = newFlowNodeForGraph(graph, detail.modelLabel)
						val next = graph.insertSerial(toolbarSerialInsertTarget(graph), node)
						applyGraphEdit(next)
					},
				) {
					Text("+ 串行节点")
				}
			}
		}
		CanvasFrame(
			graph = graph,
			onSelect = { history = history.copy(current = graph.copy(selectedNodeId = it)) },
			onInsert = { target, kind ->
				val node = newFlowNodeForGraph(graph, detail.modelLabel)
				val next = when (kind) {
					FlowInsertKind.Serial -> graph.insertSerial(target, node)
					FlowInsertKind.Parallel -> graph.insertParallel(target, node)
					FlowInsertKind.Routing -> graph.insertRouting(target, node)
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
					Text("节点设置 · ${node.title}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
					TextButton(
						onClick = {
							val next = graph.removeNode(node.id)
							applyGraphEdit(next)
						},
						enabled = node.removable,
					) {
						Text("删除节点")
					}
				}
				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.fillMaxWidth(),
				) {
					OutlinedButton(
						onClick = {
							applyGraphEdit(graph.parallelizeWithPrevious(node.id))
						},
						enabled = graph.canParallelizeWithPrevious(node.id),
						modifier = Modifier.weight(1f),
					) {
						Text("与上一节点并行")
					}
					OutlinedButton(
						onClick = {
							applyGraphEdit(graph.parallelizeWithNext(node.id))
						},
						enabled = graph.canParallelizeWithNext(node.id),
						modifier = Modifier.weight(1f),
					) {
						Text("与下一节点并行")
					}
				}
				OutlinedTextField(
					value = draftTitle,
					onValueChange = {
						draftTitle = it
						onConfigDraftChanged(detail.workUnitId)
					},
					label = { Text("节点名称") },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
				)
				OutlinedTextField(
					value = draftTask,
					onValueChange = {
						draftTask = it
						onConfigDraftChanged(detail.workUnitId)
					},
					label = { Text("任务") },
					modifier = Modifier.fillMaxWidth(),
					minLines = 3,
					maxLines = 6,
				)
				NodeModelSelector(
					selectedLabel = selectedModelLabel,
					options = modelOptions,
					enabled = modelOptions.isNotEmpty(),
					onSelect = {
						selectedModelValue = it.modelValue
						onConfigDraftChanged(detail.workUnitId)
					},
				)
				NodeModeSelector(
					selectedValue = selectedModeValue,
					onSelect = {
						selectedModeValue = it
						onConfigDraftChanged(detail.workUnitId)
					},
				)
				Button(
					onClick = {
						val next = applyOrchestrationNodeSettings(
							graph = graph,
							nodeId = node.id,
							title = draftTitle,
							task = draftTask,
							modeValue = selectedModeValue,
							modelValue = selectedModelValue,
							modelLabel = selectedModelLabel,
						)
						applyGraphEdit(next)
					},
					enabled = nodeChanged,
				) {
					Text("保存节点")
				}
			}
		}
		CompletedRunRecords(detail.completedRuns)
	}
}

@Composable
private fun CompletedRunRecords(records: List<WorkUnitCompletedRunModel>) {
	if (records.isEmpty()) {
		return
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text("已完成记录", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
		records.forEach { record ->
			Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
				Text(
					record.title,
					style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				record.completedAtLabel?.let { completedAt ->
					Text(completedAt, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
				}
				Text(record.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
			}
		}
	}
}

@Composable
private fun WorkUnitConfigConflictBanner(
	conflict: WorkUnitConfigConflict,
	onLoadLatest: () -> Unit,
	onKeepDraft: () -> Unit,
) {
	Card(
		shape = RoundedCornerShape(8.dp),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Warning.copy(alpha = 0.12f)),
		border = BorderStroke(1.dp, BaBiQColors.Warning.copy(alpha = 0.28f)),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(conflict.title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			Text(conflict.message, style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(onClick = onLoadLatest) { Text(conflict.loadLatestLabel) }
				TextButton(onClick = onKeepDraft) { Text(conflict.keepDraftLabel) }
			}
		}
	}
}

@Composable
private fun CanvasFrame(
	graph: FlowGraph,
	onSelect: (String) -> Unit,
	onInsert: (FlowInsertTarget, FlowInsertKind) -> Unit,
	onMove: (String, FlowDropTarget) -> Unit = { _, _ -> },
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(360.dp)
			.background(BaBiQColors.Background, RoundedCornerShape(10.dp))
			.border(1.dp, BaBiQColors.Border, RoundedCornerShape(10.dp))
			.padding(10.dp),
	) {
		FlowCanvas(
			graph = graph,
			modifier = Modifier.fillMaxSize(),
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
			.padding(10.dp),
	) {
		FlowCanvas(
			graph = flowGraphFromOrchestrationItem(item),
			modifier = Modifier.fillMaxSize(),
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

@Composable
private fun NodeModeSelector(
	selectedValue: String,
	onSelect: (String) -> Unit,
) {
	Column {
		Text("工具权限", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			orchestrationNodeModeOptions().forEach { option ->
				if (option.value == selectedValue) {
					Button(onClick = { onSelect(option.value) }) {
						Text(option.label, maxLines = 1)
					}
				} else {
					OutlinedButton(onClick = { onSelect(option.value) }) {
						Text(option.label, maxLines = 1)
					}
				}
			}
		}
	}
}

fun orchestrationNodeModeOptions(): List<OrchestrationNodeModeOption> =
	listOf(
		OrchestrationNodeModeOption(FlowNodeMode.ReadOnlyTool.wireValue, "只读"),
		OrchestrationNodeModeOption(FlowNodeMode.WorkspaceTool.wireValue, "全工具"),
	)

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
		role = "自定义节点",
		task = "补充这个节点的任务",
		modeLabel = orchestrationModeLabel("READ_ONLY_TOOL"),
		modeValue = "READ_ONLY_TOOL",
		modelLabel = inheritedModelLabel.ifBlank { "未选择模型" },
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
		.ifBlank { "未选择模型" }
	val options = mutableListOf(
		OrchestrationNodeModelOption(null, null, "继承主 Agent / $inheritedLabel", "inherit"),
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
		OrchestrationAddNodeAction(flowInsertKindLabel(FlowInsertKind.Serial), OrchestrationDraftAddMode.Serial),
		OrchestrationAddNodeAction(flowInsertKindLabel(FlowInsertKind.Parallel), OrchestrationDraftAddMode.Parallel),
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
		modeLabel = orchestrationModeLabel(mode.wireValue),
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
		role = "目标入口",
		task = currentGoal,
		modeLabel = "目标",
		modeValue = "GOAL",
		modelLabel = "当前目标",
		modelValue = "goal:current",
		selected = false,
		removable = false,
	)

private fun endConfigNode(): OrchestrationConfigNodeRow =
	configNode(
		nodeId = "end",
		title = "END",
		role = "流程出口",
		task = "所有节点完成后由主 Agent 确认最终输出。",
		modeLabel = "结束",
		modeValue = "END",
		modelLabel = "主 Agent",
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
		nodeTitle = node.title,
		title = "节点设置 · ${node.title}",
		task = node.task,
		modelLabel = node.modelLabel,
		modelValue = node.modelValue,
		modeLabel = node.modeLabel,
		modeValue = node.modeValue,
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
			orchestrationModeLabel(node.mode),
			node.model?.takeIf { it.isNotBlank() },
			node.toolCallCount?.let { "工具 $it" },
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
		Text("流程摘要", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
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
		"idle", "pending", "waiting_config" -> "待配置"
		"running" -> "运行中"
		"completed" -> "已完成"
		"failed" -> "失败"
		"canceled" -> "已取消"
		else -> status
	}

private fun topologyLabel(topology: String): String =
	when (topology.lowercase()) {
		"sequential" -> "串行"
		"parallel" -> "并行"
		"routing" -> "路由"
		else -> topology
	}

fun orchestrationModeLabel(mode: String): String =
	when (mode.uppercase()) {
		"READ_ONLY_TOOL" -> "只读"
		"WORKSPACE_TOOL" -> "全工具"
		"GOAL" -> "目标"
		"END" -> "结束"
		else -> mode
	}

private fun approvalLabel(item: ThreadItem.Orchestration): String =
	if (item.approved == true && item.frozen == true) "已审批并冻结" else "未冻结"

private fun displayModelLabel(modelValue: String, modelLabel: String): String =
	when {
		modelValue.startsWith("goal:") -> "当前目标"
		modelValue.startsWith("end:") -> "主 Agent 确认输出"
		modelValue.startsWith("provider:") -> modelValue.removePrefix("provider:").replace(":", " / ")
		modelLabel.startsWith("继承主 Agent /") -> modelLabel
		else -> "继承主 Agent / $modelLabel"
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
