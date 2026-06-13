package com.wzx.babiq.desktop.flowcanvas

enum class FlowTopology(val wireValue: String) {
	Sequential("sequential"),
	Parallel("parallel"),
	Routing("routing");

	companion object {
		fun from(value: String?): FlowTopology =
			entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
				?: Sequential
	}
}

enum class FlowNodeMode(val wireValue: String) {
	ReadOnlyTool("READ_ONLY_TOOL"),
	WorkspaceTool("WORKSPACE_TOOL"),
	Goal("GOAL"),
	End("END");

	companion object {
		fun from(value: String?): FlowNodeMode =
			entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
				?: ReadOnlyTool
	}
}

enum class FlowNodeStatus {
	Pending,
	Running,
	Completed,
	Failed,
	Canceled;

	companion object {
		fun from(value: String?): FlowNodeStatus =
			entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Pending
	}
}

data class FlowNode(
	val id: String,
	val title: String,
	val role: String,
	val task: String,
	val mode: FlowNodeMode = FlowNodeMode.ReadOnlyTool,
	val status: FlowNodeStatus = FlowNodeStatus.Pending,
	val modelLabel: String = "inherit",
	val modelValue: String = "inherit",
	val errorSummary: String? = null,
	val removable: Boolean = true,
)

/**
 * FlowDropTarget 描述一次拖拽释放后的结构目标。
 *
 * 当前画布仍是受限树，不保存自由坐标；拖拽只表达“放到某节点之后”或“放进某个组”。
 */
sealed interface FlowDropTarget {
	data class AfterNode(val nodeId: String) : FlowDropTarget
	data class IntoGroup(val groupId: String) : FlowDropTarget
}

sealed interface FlowEntry {
	data class NodeRef(val nodeId: String) : FlowEntry

	data class Group(
		val groupId: String,
		val topology: FlowTopology,
		val children: List<FlowEntry> = emptyList(),
	) : FlowEntry
}

data class FlowGraph(
	val nodes: List<FlowNode> = emptyList(),
	val root: FlowEntry.Group = FlowEntry.Group("g_root", FlowTopology.Sequential),
	val selectedNodeId: String? = nodes.firstOrNull()?.id,
) {
	val nodeMap: Map<String, FlowNode> = nodes.associateBy { it.id }
	val selectedNode: FlowNode? = selectedNodeId?.let(nodeMap::get)

	init {
		validate()
	}

	fun flattenNodeIds(): List<String> = root.flattenNodeIds()

	fun nextNodeId(prefix: String = "node"): String {
		val used = nodeMap.keys
		return generateSequence(1) { it + 1 }
			.map { "${prefix}_$it" }
			.first { it !in used }
	}

	fun replaceNode(node: FlowNode): FlowGraph =
		copy(nodes = nodes.map { if (it.id == node.id) node else it })

	fun insertSerial(afterNodeId: String?, node: FlowNode): FlowGraph =
		insertNode(afterNodeId, node, InsertMode.Serial)

	fun insertParallel(anchorNodeId: String?, node: FlowNode): FlowGraph =
		insertNode(anchorNodeId, node, InsertMode.Parallel)

	fun insertRouting(anchorNodeId: String?, node: FlowNode): FlowGraph =
		insertNode(anchorNodeId, node, InsertMode.Routing)

	/**
	 * 移动一个已有节点引用，不改变节点自身配置。
	 *
	 * 该方法先从结构树移除引用，再按目标位置重新插入；构造完成后仍会走 init 校验，
	 * 因而深度和唯一引用约束会继续被测试锁住。
	 */
	fun moveEntry(nodeId: String, target: FlowDropTarget): FlowGraph {
		if (nodeId !in nodeMap || nodeMap[nodeId]?.removable == false) {
			return this
		}
		if (target is FlowDropTarget.AfterNode && target.nodeId == nodeId) {
			return this
		}
		if (target is FlowDropTarget.AfterNode && !root.containsNode(target.nodeId)) {
			return this
		}
		if (target is FlowDropTarget.IntoGroup && !root.containsGroup(target.groupId)) {
			return this
		}
		val withoutNode = root.removeNode(nodeId)
		val nextRoot = when (target) {
			is FlowDropTarget.AfterNode -> withoutNode.insertSerial(target.nodeId, nodeId)
			is FlowDropTarget.IntoGroup -> withoutNode.insertIntoGroup(target.groupId, nodeId) ?: return this
		}
		return copy(root = nextRoot, selectedNodeId = nodeId)
	}

	fun removeNode(nodeId: String): FlowGraph {
		if (nodeId !in nodeMap || nodeMap[nodeId]?.removable == false) {
			return this
		}
		val nextRoot = root.removeNode(nodeId)
		val nextNodes = nodes.filterNot { it.id == nodeId }
		val nextSelection = when {
			selectedNodeId != nodeId -> selectedNodeId
			else -> nextRoot.flattenNodeIds().firstOrNull()
		}
		return copy(nodes = nextNodes, root = nextRoot, selectedNodeId = nextSelection)
	}

	private fun insertNode(afterNodeId: String?, node: FlowNode, mode: InsertMode): FlowGraph {
		require(node.id.isNotBlank()) { "node id must not be blank" }
		require(node.id !in nodeMap) { "duplicate node id: ${node.id}" }
		val nextRoot = when (mode) {
			InsertMode.Serial -> root.insertSerial(afterNodeId, node.id)
			InsertMode.Parallel -> root.insertGroup(afterNodeId, node.id, FlowTopology.Parallel)
			InsertMode.Routing -> root.insertGroup(afterNodeId, node.id, FlowTopology.Routing)
		}
		return copy(nodes = nodes + node, root = nextRoot, selectedNodeId = node.id)
	}

	private fun validate() {
		val referenced = flattenNodeIds()
		val referencedSet = referenced.toSet()
		require(referenced.size == referencedSet.size) { "flow node can only be referenced once" }
		require(referencedSet.all { it in nodeMap }) { "flow structure references missing nodes" }
		require(nodeMap.keys.all { it in referencedSet }) { "flow has nodes outside structure" }
		root.validateDepth(depth = 0)
	}
}

data class FlowGraphHistory(
	val current: FlowGraph,
	val undoStack: List<FlowGraph> = emptyList(),
	val redoStack: List<FlowGraph> = emptyList(),
	val maxDepth: Int = 20,
) {
	fun apply(next: FlowGraph): FlowGraphHistory =
		copy(
			current = next,
			undoStack = (undoStack + current).takeLast(maxDepth),
			redoStack = emptyList(),
		)

	fun undo(): FlowGraphHistory =
		if (undoStack.isEmpty()) {
			this
		} else {
			copy(
				current = undoStack.last(),
				undoStack = undoStack.dropLast(1),
				redoStack = listOf(current) + redoStack,
			)
		}

	fun redo(): FlowGraphHistory =
		if (redoStack.isEmpty()) {
			this
		} else {
			copy(
				current = redoStack.first(),
				undoStack = undoStack + current,
				redoStack = redoStack.drop(1),
			)
		}
}

private enum class InsertMode {
	Serial,
	Parallel,
	Routing,
}

private fun FlowEntry.Group.insertSerial(afterNodeId: String?, nodeId: String): FlowEntry.Group {
	if (afterNodeId == null || children.isEmpty()) {
		return copy(children = children + FlowEntry.NodeRef(nodeId))
	}
	val index = children.indexOfFirst { it.containsNode(afterNodeId) }
	if (index < 0) {
		return copy(children = children + FlowEntry.NodeRef(nodeId))
	}
	val child = children[index]
	return if (child is FlowEntry.Group && child.topology != FlowTopology.Sequential && child.containsNode(afterNodeId)) {
		copy(children = children.updateAt(index, child.insertSerial(afterNodeId, nodeId)))
	} else {
		copy(children = children.take(index + 1) + FlowEntry.NodeRef(nodeId) + children.drop(index + 1))
	}
}

private fun FlowEntry.Group.insertGroup(anchorNodeId: String?, nodeId: String, topology: FlowTopology): FlowEntry.Group {
	if (anchorNodeId == null || children.isEmpty()) {
		return copy(children = children + FlowEntry.NodeRef(nodeId))
	}
	val index = children.indexOfFirst { it.containsNode(anchorNodeId) }
	if (index < 0) {
		return copy(children = children + FlowEntry.NodeRef(nodeId))
	}
	val child = children[index]
	return when (child) {
		is FlowEntry.Group -> {
			if (child.topology == FlowTopology.Parallel || child.topology == FlowTopology.Routing) {
				copy(children = children.updateAt(index, child.copy(children = child.children + FlowEntry.NodeRef(nodeId))))
			} else {
				copy(children = children.take(index + 1) + FlowEntry.NodeRef(nodeId) + children.drop(index + 1))
			}
		}
		is FlowEntry.NodeRef -> {
			val group = FlowEntry.Group(
				groupId = "g_$anchorNodeId",
				topology = topology,
				children = listOf(child, FlowEntry.NodeRef(nodeId)),
			)
			copy(children = children.updateAt(index, group))
		}
	}
}

private fun FlowEntry.Group.removeNode(nodeId: String): FlowEntry.Group =
	copy(
		children = children.mapNotNull { child ->
			when (child) {
				is FlowEntry.NodeRef -> child.takeIf { it.nodeId != nodeId }
				is FlowEntry.Group -> child.removeNode(nodeId).collapseIfSingle()
			}
		},
	)

private fun FlowEntry.Group.insertIntoGroup(groupId: String, nodeId: String): FlowEntry.Group? {
	if (this.groupId == groupId) {
		return copy(children = children + FlowEntry.NodeRef(nodeId))
	}
	var inserted = false
	val nextChildren = children.map { child ->
		if (child is FlowEntry.Group) {
			val next = child.insertIntoGroup(groupId, nodeId)
			if (next != null) {
				inserted = true
				next
			} else {
				child
			}
		} else {
			child
		}
	}
	return if (inserted) copy(children = nextChildren) else null
}

private fun FlowEntry.Group.collapseIfSingle(): FlowEntry =
	if (topology != FlowTopology.Sequential && children.size == 1) {
		children.single()
	} else {
		this
	}

private fun FlowEntry.Group.validateDepth(depth: Int) {
	require(depth <= 1) { "flow canvas only supports one nested group level" }
	children.forEach { child ->
		if (child is FlowEntry.Group) {
			child.validateDepth(depth + 1)
		}
	}
}

private fun FlowEntry.flattenNodeIds(): List<String> =
	when (this) {
		is FlowEntry.NodeRef -> listOf(nodeId)
		is FlowEntry.Group -> children.flatMap { it.flattenNodeIds() }
	}

private fun FlowEntry.containsNode(nodeId: String): Boolean =
	when (this) {
		is FlowEntry.NodeRef -> this.nodeId == nodeId
		is FlowEntry.Group -> children.any { it.containsNode(nodeId) }
	}

private fun FlowEntry.containsGroup(groupId: String): Boolean =
	when (this) {
		is FlowEntry.NodeRef -> false
		is FlowEntry.Group -> this.groupId == groupId || children.any { it.containsGroup(groupId) }
	}

private fun <T> List<T>.updateAt(index: Int, value: T): List<T> =
	mapIndexed { currentIndex, current -> if (currentIndex == index) value else current }
