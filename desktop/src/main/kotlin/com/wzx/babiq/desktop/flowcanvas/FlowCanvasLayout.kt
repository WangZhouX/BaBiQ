package com.wzx.babiq.desktop.flowcanvas

data class FlowPoint(val x: Float, val y: Float)
data class FlowSize(val width: Float, val height: Float)
data class FlowRect(val x: Float, val y: Float, val width: Float, val height: Float) {
	val center: FlowPoint
		get() = FlowPoint(x + width / 2f, y + height / 2f)

	val topCenter: FlowPoint
		get() = FlowPoint(x + width / 2f, y)

	val bottomCenter: FlowPoint
		get() = FlowPoint(x + width / 2f, y + height)

	val leftCenter: FlowPoint
		get() = FlowPoint(x, y + height / 2f)

	val rightCenter: FlowPoint
		get() = FlowPoint(x + width, y + height / 2f)
}

data class FlowCanvasLayoutConfig(
	val nodeWidth: Float = 132f,
	val nodeHeight: Float = 64f,
	val terminalWidth: Float = 64f,
	val terminalHeight: Float = 30f,
	val horizontalGap: Float = 34f,
	val verticalGap: Float = 42f,
	val padding: Float = 16f,
)

data class FlowCanvasNodeLayout(
	val nodeId: String,
	val rect: FlowRect,
	val groupId: String? = null,
)

data class FlowCanvasTerminalLayout(
	val id: String,
	val label: String,
	val rect: FlowRect,
)

data class FlowCanvasEdgeLayout(
	val id: String,
	val from: FlowPoint,
	val to: FlowPoint,
	val hasArrow: Boolean,
	val insertAnchorNodeId: String?,
)

data class FlowCanvasInsertPoint(
	val id: String,
	val anchorNodeId: String?,
	val center: FlowPoint,
	val target: FlowInsertTarget = anchorNodeId?.let(FlowInsertTarget::AfterNode) ?: FlowInsertTarget.Append,
)

data class FlowCanvasLayoutResult(
	val size: FlowSize,
	val start: FlowCanvasTerminalLayout,
	val end: FlowCanvasTerminalLayout,
	val nodes: List<FlowCanvasNodeLayout>,
	val edges: List<FlowCanvasEdgeLayout>,
	val insertPoints: List<FlowCanvasInsertPoint>,
)

fun layoutFlowCanvas(
	graph: FlowGraph,
	config: FlowCanvasLayoutConfig = FlowCanvasLayoutConfig(),
): FlowCanvasLayoutResult {
	val content = layoutEntry(graph.root, config, config.padding, config.padding + config.terminalHeight + config.verticalGap)
	val width = maxOf(
		content.size.width + config.padding * 2f,
		config.terminalWidth + config.padding * 2f,
	)
	val startRect = FlowRect(
		x = (width - config.terminalWidth) / 2f,
		y = config.padding,
		width = config.terminalWidth,
		height = config.terminalHeight,
	)
	val nodeStart = content.firstTopCenter ?: FlowPoint(width / 2f, startRect.bottomCenter.y + config.verticalGap)
	val endY = content.boundsBottom + config.verticalGap
	val endRect = FlowRect(
		x = (width - config.terminalWidth) / 2f,
		y = endY,
		width = config.terminalWidth,
		height = config.terminalHeight,
	)
	val edges = mutableListOf<FlowCanvasEdgeLayout>()
	val insertPoints = mutableListOf<FlowCanvasInsertPoint>()
	edges += FlowCanvasEdgeLayout(
		id = "edge_start",
		from = startRect.bottomCenter,
		to = nodeStart,
		hasArrow = graph.nodes.isNotEmpty(),
		insertAnchorNodeId = null,
	)
	val startTarget = if (graph.root.children.isEmpty()) FlowInsertTarget.Append else FlowInsertTarget.Prepend
	insertPoints += FlowCanvasInsertPoint(
		id = "insert_start",
		anchorNodeId = null,
		center = midpoint(startRect.bottomCenter, nodeStart),
		target = startTarget,
	)
	edges += content.edges
	insertPoints += content.insertPoints
	val lastPoint = content.lastBottomCenter ?: startRect.bottomCenter
	val endTarget = graph.root.children.lastOrNull()?.insertAfterTarget() ?: FlowInsertTarget.Append
	val endAnchor = endTarget.anchorNodeId()
	edges += FlowCanvasEdgeLayout(
		id = "edge_end",
		from = lastPoint,
		to = endRect.topCenter,
		hasArrow = graph.nodes.isNotEmpty(),
		insertAnchorNodeId = endAnchor,
	)
	insertPoints += FlowCanvasInsertPoint("insert_end", endAnchor, midpoint(lastPoint, endRect.topCenter), endTarget)
	return FlowCanvasLayoutResult(
		size = FlowSize(width, endRect.y + endRect.height + config.padding),
		start = FlowCanvasTerminalLayout("start", "START", startRect),
		end = FlowCanvasTerminalLayout("end", "END", endRect),
		nodes = content.nodes,
		edges = edges,
		insertPoints = insertPoints,
	)
}

private data class EntryLayout(
	val size: FlowSize,
	val nodes: List<FlowCanvasNodeLayout>,
	val edges: List<FlowCanvasEdgeLayout>,
	val insertPoints: List<FlowCanvasInsertPoint>,
	val firstTopCenter: FlowPoint?,
	val lastBottomCenter: FlowPoint?,
	val boundsBottom: Float,
)

private fun layoutEntry(
	entry: FlowEntry,
	config: FlowCanvasLayoutConfig,
	x: Float,
	y: Float,
	groupId: String? = null,
): EntryLayout =
	when (entry) {
		is FlowEntry.NodeRef -> layoutNode(entry, config, x, y, groupId)
		is FlowEntry.Group -> when (entry.topology) {
			FlowTopology.Sequential -> layoutSequentialGroup(entry, config, x, y)
			FlowTopology.Parallel, FlowTopology.Routing -> layoutParallelGroup(entry, config, x, y)
		}
	}

private fun layoutNode(
	node: FlowEntry.NodeRef,
	config: FlowCanvasLayoutConfig,
	x: Float,
	y: Float,
	groupId: String?,
): EntryLayout {
	val rect = FlowRect(x, y, config.nodeWidth, config.nodeHeight)
	return EntryLayout(
		size = FlowSize(config.nodeWidth, config.nodeHeight),
		nodes = listOf(FlowCanvasNodeLayout(node.nodeId, rect, groupId)),
		edges = emptyList(),
		insertPoints = emptyList(),
		firstTopCenter = rect.topCenter,
		lastBottomCenter = rect.bottomCenter,
		boundsBottom = rect.y + rect.height,
	)
}

private fun layoutSequentialGroup(
	group: FlowEntry.Group,
	config: FlowCanvasLayoutConfig,
	x: Float,
	y: Float,
): EntryLayout {
	if (group.children.isEmpty()) {
		return EntryLayout(
			size = FlowSize(config.nodeWidth, 0f),
			nodes = emptyList(),
			edges = emptyList(),
			insertPoints = emptyList(),
			firstTopCenter = null,
			lastBottomCenter = null,
			boundsBottom = y,
		)
	}
	val nodes = mutableListOf<FlowCanvasNodeLayout>()
	val edges = mutableListOf<FlowCanvasEdgeLayout>()
	val inserts = mutableListOf<FlowCanvasInsertPoint>()
	var currentY = y
	var maxWidth = config.nodeWidth
	var previousBottom: FlowPoint? = null
	var previousAnchor: String? = null
	var previousTarget: FlowInsertTarget? = null
	var firstTop: FlowPoint? = null
	var lastBottom: FlowPoint? = null
	group.children.forEachIndexed { index, child ->
		val childLayout = layoutEntry(child, config, x, currentY, group.groupId.takeIf { group.topology != FlowTopology.Sequential })
		nodes += childLayout.nodes
		edges += childLayout.edges
		inserts += childLayout.insertPoints
		if (firstTop == null) {
			firstTop = childLayout.firstTopCenter
		}
		val childTop = childLayout.firstTopCenter
		val from = previousBottom
		if (from != null && childTop != null) {
			val anchor = previousAnchor
			val target = previousTarget ?: FlowInsertTarget.Append
			edges += FlowCanvasEdgeLayout(
				id = "edge_${index}_${anchor ?: "start"}",
				from = from,
				to = childTop,
				hasArrow = true,
				insertAnchorNodeId = anchor,
			)
			inserts += FlowCanvasInsertPoint("insert_${index}_${anchor ?: "start"}", anchor, midpoint(from, childTop), target)
		}
		previousBottom = childLayout.lastBottomCenter
		previousTarget = child.insertAfterTarget()
		previousAnchor = previousTarget.anchorNodeId() ?: child.flattenNodeIds().lastOrNull()
		lastBottom = childLayout.lastBottomCenter
		maxWidth = maxOf(maxWidth, childLayout.size.width)
		currentY = childLayout.boundsBottom + config.verticalGap
	}
	return EntryLayout(
		size = FlowSize(maxWidth, currentY - y - config.verticalGap),
		nodes = nodes,
		edges = edges,
		insertPoints = inserts,
		firstTopCenter = firstTop,
		lastBottomCenter = lastBottom,
		boundsBottom = currentY - config.verticalGap,
	)
}

private fun layoutParallelGroup(
	group: FlowEntry.Group,
	config: FlowCanvasLayoutConfig,
	x: Float,
	y: Float,
): EntryLayout {
	if (group.children.isEmpty()) {
		return layoutSequentialGroup(group.copy(topology = FlowTopology.Sequential), config, x, y)
	}
	var currentX = x
	val children = group.children.map { child ->
		val layout = layoutEntry(child, config, currentX, y, group.groupId)
		currentX += layout.size.width + config.horizontalGap
		layout
	}
	val totalWidth = children.sumOf { it.size.width.toDouble() }.toFloat() +
		config.horizontalGap * (children.size - 1).coerceAtLeast(0)
	val top = FlowPoint(x + totalWidth / 2f, y - config.verticalGap / 2f)
	val bottomY = children.maxOf { it.boundsBottom }
	val bottom = FlowPoint(x + totalWidth / 2f, bottomY + config.verticalGap / 2f)
	val edges = mutableListOf<FlowCanvasEdgeLayout>()
	val inserts = mutableListOf<FlowCanvasInsertPoint>()
	children.forEachIndexed { index, layout ->
		val first = layout.firstTopCenter
		val last = layout.lastBottomCenter
		val branchTarget = FlowInsertTarget.IntoGroup(group.groupId, group.topology)
		val branchAnchor = layout.nodes.lastOrNull()?.nodeId
		if (first != null) {
			edges += FlowCanvasEdgeLayout(
				id = "edge_${group.groupId}_fanout_$index",
				from = top,
				to = first,
				hasArrow = false,
				insertAnchorNodeId = branchAnchor,
			)
			inserts += FlowCanvasInsertPoint("insert_${group.groupId}_fanout_$index", branchAnchor, midpoint(top, first), branchTarget)
		}
		if (last != null) {
			edges += FlowCanvasEdgeLayout(
				id = "edge_${group.groupId}_join_$index",
				from = last,
				to = bottom,
				hasArrow = false,
				insertAnchorNodeId = layout.nodes.lastOrNull()?.nodeId,
			)
		}
		edges += layout.edges
		inserts += layout.insertPoints
	}
	return EntryLayout(
		size = FlowSize(totalWidth, bottom.y - top.y),
		nodes = children.flatMap { it.nodes },
		edges = edges,
		insertPoints = inserts,
		firstTopCenter = top,
		lastBottomCenter = bottom,
		boundsBottom = bottom.y,
	)
}

private fun FlowEntry.flattenNodeIds(): List<String> =
	when (this) {
		is FlowEntry.NodeRef -> listOf(nodeId)
		is FlowEntry.Group -> children.flatMap { it.flattenNodeIds() }
	}

private fun FlowEntry.insertAfterTarget(): FlowInsertTarget =
	when (this) {
		is FlowEntry.NodeRef -> FlowInsertTarget.AfterNode(nodeId)
		is FlowEntry.Group -> FlowInsertTarget.AfterGroup(groupId)
	}

private fun FlowInsertTarget.anchorNodeId(): String? =
	when (this) {
		FlowInsertTarget.Append -> null
		FlowInsertTarget.Prepend -> null
		is FlowInsertTarget.AfterNode -> nodeId
		is FlowInsertTarget.AfterGroup -> null
		is FlowInsertTarget.IntoGroup -> null
	}

private fun midpoint(a: FlowPoint, b: FlowPoint): FlowPoint =
	FlowPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
