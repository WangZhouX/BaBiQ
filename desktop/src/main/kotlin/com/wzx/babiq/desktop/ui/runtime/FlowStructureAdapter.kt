package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.flowcanvas.FlowEntry
import com.wzx.babiq.desktop.flowcanvas.FlowGraph
import com.wzx.babiq.desktop.flowcanvas.FlowNode
import com.wzx.babiq.desktop.flowcanvas.FlowNodeMode
import com.wzx.babiq.desktop.flowcanvas.FlowNodeStatus
import com.wzx.babiq.desktop.flowcanvas.FlowTopology
import com.wzx.babiq.desktop.protocol.FlowEntryDto
import com.wzx.babiq.desktop.protocol.FlowStructureDto
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.WorkUnitConfigEntry
import com.wzx.babiq.desktop.protocol.WorkUnitConfiguration
import com.wzx.babiq.desktop.protocol.protocolJson
import kotlinx.serialization.encodeToString

fun flowGraphFromWorkUnitDetail(detail: WorkUnitDetailModel): FlowGraph {
	val savedEntries = detail.configuration?.nodes.orEmpty()
		.filterNot { it.id.isTerminalNodeId() }
	val entries = savedEntries.ifEmpty {
		explicitNodeIdsFromGoal(currentGoalText(detail)).map { nodeId ->
			WorkUnitConfigEntry(
				id = nodeId,
				name = nodeId,
				role = defaultRoleForFlowNode(nodeId),
				task = explicitNodeTask(currentGoalText(detail), nodeId) ?: "补充这个节点的任务",
				mode = defaultModeForFlowNode(nodeId).wireValue,
				model = "inherit",
			)
		}
	}
	val nodes = entries.map { it.toFlowNode(detail.modelLabel) }
	val structure = detail.structure?.root?.toFlowEntry()
		?: legacyRoot(detail.configuration?.topology, nodes.map { it.id })
	return FlowGraph(
		nodes = nodes,
		root = structure.ensureRootGroup(nodes.map { it.id }),
		selectedNodeId = nodes.firstOrNull()?.id,
	)
}

fun flowGraphFromOrchestrationItem(item: ThreadItem.Orchestration): FlowGraph {
	val rawNodes = item.nodes.map { node ->
		val status = FlowNodeStatus.from(node.status)
		FlowNode(
			id = node.nodeId,
			title = node.displayName?.takeIf { it.isNotBlank() } ?: node.name,
			role = node.name,
			task = node.summary?.takeIf { it.isNotBlank() } ?: node.task.orEmpty(),
			mode = FlowNodeMode.from(node.mode),
			status = status,
			modelLabel = node.model ?: "inherit",
			modelValue = node.model ?: "inherit",
			errorSummary = node.summary?.takeIf { status == FlowNodeStatus.Failed && it.isNotBlank() },
		)
	}
	val failedIndex = rawNodes.indexOfFirst { it.status == FlowNodeStatus.Failed }
	val nodes = if (failedIndex < 0) {
		rawNodes
	} else {
		rawNodes.mapIndexed { index, node ->
			if (index > failedIndex && node.status == FlowNodeStatus.Pending) {
				node.copy(status = FlowNodeStatus.Canceled)
			} else {
				node
			}
		}
	}
	val structure = item.structureJson
		?.takeIf { it.isNotBlank() }
		?.let { json -> runCatching { protocolJson.decodeFromString<FlowStructureDto>(json) }.getOrNull() }
		?.root
		?.toFlowEntry()
		?: legacyRoot(item.topology, nodes.map { it.id })
	return FlowGraph(
		nodes = nodes,
		root = structure.ensureRootGroup(nodes.map { it.id }),
		selectedNodeId = nodes.firstOrNull { it.status == FlowNodeStatus.Running }?.id ?: nodes.firstOrNull()?.id,
	)
}

fun buildFlowConfigJson(detail: WorkUnitDetailModel, graph: FlowGraph): String =
	protocolJson.encodeToString(
		WorkUnitConfiguration(
			topology = graph.root.topology.wireValue,
			nodes = graph.nodes.map { node ->
				WorkUnitConfigEntry(
					id = node.id,
					name = node.title,
					role = node.role,
					task = node.task,
					model = node.modelValue,
					mode = node.mode.wireValue,
				)
			},
			members = detail.configuration?.members.orEmpty(),
		),
	)

fun buildFlowStructureJson(graph: FlowGraph): String =
	protocolJson.encodeToString(FlowStructureDto(graph.root.toDto()))

fun newFlowNodeForGraph(graph: FlowGraph, inheritedModelLabel: String): FlowNode {
	val id = graph.nextNodeId()
	return FlowNode(
		id = id,
		title = id,
		role = "自定义节点",
		task = "补充这个节点的任务",
		mode = FlowNodeMode.ReadOnlyTool,
		modelLabel = "继承主 Agent / ${inheritedModelLabel.ifBlank { "当前模型" }}",
		modelValue = "inherit",
	)
}

private fun WorkUnitConfigEntry.toFlowNode(inheritedModelLabel: String): FlowNode {
	val modeValue = mode?.takeIf { it.isNotBlank() } ?: defaultModeForFlowNode(id).wireValue
	val modelValue = model?.takeIf { it.isNotBlank() } ?: "inherit"
	return FlowNode(
		id = id,
		title = name?.takeIf { it.isNotBlank() } ?: defaultTitleForFlowNode(id),
		role = role?.takeIf { it.isNotBlank() } ?: defaultRoleForFlowNode(id),
		task = task?.takeIf { it.isNotBlank() } ?: "补充这个节点的任务",
		mode = FlowNodeMode.from(modeValue),
		modelLabel = displayFlowModelLabel(modelValue, inheritedModelLabel),
		modelValue = modelValue,
	)
}

private fun FlowEntryDto.toFlowEntry(): FlowEntry =
	if (!nodeId.isNullOrBlank()) {
		FlowEntry.NodeRef(nodeId)
	} else {
		FlowEntry.Group(
			groupId = groupId?.takeIf { it.isNotBlank() } ?: "g_root",
			topology = FlowTopology.from(topology),
			children = children.map { it.toFlowEntry() },
		)
	}

private fun FlowEntry.toDto(): FlowEntryDto =
	when (this) {
		is FlowEntry.NodeRef -> FlowEntryDto(nodeId = nodeId)
		is FlowEntry.Group -> FlowEntryDto(
			groupId = groupId,
			topology = topology.wireValue.uppercase(),
			children = children.map { it.toDto() },
		)
	}

private fun legacyRoot(topology: String?, nodeIds: List<String>): FlowEntry.Group =
	FlowEntry.Group(
		groupId = "g_root",
		topology = FlowTopology.from(topology),
		children = nodeIds.map { FlowEntry.NodeRef(it) },
	)

private fun FlowEntry.ensureRootGroup(validNodeIds: List<String>): FlowEntry.Group {
	val valid = validNodeIds.toSet()
	val normalized = filterInvalidNodes(valid)
	val group = when (normalized) {
		is FlowEntry.Group -> normalized
		is FlowEntry.NodeRef -> FlowEntry.Group("g_root", FlowTopology.Sequential, listOf(normalized))
	}
	val referenced = group.flattenNodeIds().toSet()
	val missing = validNodeIds.filterNot { it in referenced }.map { FlowEntry.NodeRef(it) }
	return if (missing.isEmpty()) group else group.copy(children = group.children + missing)
}

private fun FlowEntry.filterInvalidNodes(validNodeIds: Set<String>): FlowEntry =
	when (this) {
		is FlowEntry.NodeRef -> this
		is FlowEntry.Group -> copy(
			children = children.mapNotNull { child ->
				when (child) {
					is FlowEntry.NodeRef -> child.takeIf { it.nodeId in validNodeIds }
					is FlowEntry.Group -> child.filterInvalidNodes(validNodeIds)
				}
			},
		)
	}

private fun FlowEntry.flattenNodeIds(): List<String> =
	when (this) {
		is FlowEntry.NodeRef -> listOf(nodeId)
		is FlowEntry.Group -> children.flatMap { it.flattenNodeIds() }
	}

private fun String.isTerminalNodeId(): Boolean =
	equals("start", ignoreCase = true) || equals("end", ignoreCase = true)

private fun defaultTitleForFlowNode(nodeId: String): String =
	nodeId.ifBlank { "node" }

private fun defaultRoleForFlowNode(nodeId: String): String =
	when (nodeId.lowercase()) {
		"explorer" -> "探索节点"
		"designer" -> "方案设计"
		"writer" -> "写入节点"
		"reviewer" -> "复核节点"
		"tester" -> "测试节点"
		else -> "自定义节点"
	}

private fun defaultModeForFlowNode(nodeId: String): FlowNodeMode =
	when (nodeId.lowercase()) {
		"writer", "tester" -> FlowNodeMode.WorkspaceTool
		else -> FlowNodeMode.ReadOnlyTool
	}

private fun displayFlowModelLabel(modelValue: String, inheritedModel: String): String =
	when {
		modelValue.startsWith("provider:") -> modelValue.removePrefix("provider:").replace(":", " / ")
		else -> "继承主 Agent / ${inheritedModel.ifBlank { "当前模型" }}"
	}

private fun currentGoalText(detail: WorkUnitDetailModel): String =
	detail.editableGoalText
		?: detail.goals.lastOrNull()?.label?.substringAfter(" - ")?.trim()
		?: detail.title

private fun explicitNodeIdsFromGoal(goal: String): List<String> =
	ExplicitNodePattern.findAll(goal)
		.map { it.groupValues[1].trim().lowercase() }
		.filterNot { it == "start" || it == "end" }
		.distinct()
		.toList()

private fun explicitNodeTask(goal: String, nodeId: String): String? {
	val pattern = Regex(
		"""(?im)^\s*(?:[-*]|\d+[.)、]?)?\s*${Regex.escape(nodeId)}\s*(?:节点|node)?\s*[:：-]?\s*(.+)$""",
	)
	return pattern.find(goal)
		?.groupValues
		?.getOrNull(1)
		?.trim()
		?.takeIf { it.isNotBlank() }
}

private val ExplicitNodePattern = Regex(
	"""\b([A-Za-z][A-Za-z0-9_-]{1,31})\s*(?:节点|node)""",
	RegexOption.IGNORE_CASE,
)
