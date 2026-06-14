package com.wzx.babiq.desktop.flowcanvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class FlowCanvasMode {
	Edit,
	Playback,
}

enum class FlowInsertKind {
	Serial,
	Parallel,
	Routing,
}

fun flowInsertKindLabel(kind: FlowInsertKind): String =
	when (kind) {
		FlowInsertKind.Serial -> "串行节点"
		FlowInsertKind.Parallel -> "并行节点"
		FlowInsertKind.Routing -> "路由分支"
	}

internal const val FlowInsertButtonDiameterDp = 16f
private const val FlowInsertButtonHitRadiusDp = 14f

internal fun flowCanvasViewportSize(content: FlowSize, minWidth: Float, minHeight: Float): FlowSize =
	FlowSize(
		width = maxOf(content.width, minWidth),
		height = maxOf(content.height, minHeight),
	)

internal fun flowCanvasCenterOffset(viewport: FlowSize, content: FlowSize): FlowPoint =
	FlowPoint(
		x = ((viewport.width - content.width) / 2f).coerceAtLeast(0f),
		y = ((viewport.height - content.height) / 2f).coerceAtLeast(0f),
	)

internal enum class FlowCanvasHitTarget {
	Background,
	Node,
	Terminal,
	InsertAnchor,
}

internal fun flowCanvasScreenToLayoutPoint(
	screenX: Float,
	screenY: Float,
	camera: FlowCanvasCamera,
	centerOffset: FlowPoint,
	density: Float,
): FlowPoint =
	FlowPoint(
		x = ((screenX - camera.offsetX - centerOffset.x * density) / camera.scale) / density,
		y = ((screenY - camera.offsetY - centerOffset.y * density) / camera.scale) / density,
	)

internal fun flowCanvasHitTarget(layout: FlowCanvasLayoutResult, point: FlowPoint): FlowCanvasHitTarget =
	when {
		layout.nodes.any { it.rect.contains(point) } -> FlowCanvasHitTarget.Node
		layout.start.rect.contains(point) || layout.end.rect.contains(point) -> FlowCanvasHitTarget.Terminal
		layout.insertPoints.any { it.center.distanceSquared(point) <= FlowInsertButtonHitRadiusDp * FlowInsertButtonHitRadiusDp } ->
			FlowCanvasHitTarget.InsertAnchor
		else -> FlowCanvasHitTarget.Background
	}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun FlowCanvas(
	graph: FlowGraph,
	modifier: Modifier = Modifier,
	mode: FlowCanvasMode = FlowCanvasMode.Edit,
	palette: FlowCanvasPalette = FlowCanvasPalette(),
	layoutConfig: FlowCanvasLayoutConfig = FlowCanvasLayoutConfig(),
	onSelectNode: (String) -> Unit = {},
	onInsert: (FlowInsertTarget, FlowInsertKind) -> Unit = { _, _ -> },
	onMove: (String, FlowDropTarget) -> Unit = { _, _ -> },
) {
	val layout = remember(graph, layoutConfig) { layoutFlowCanvas(graph, layoutConfig) }
	val density = LocalDensity.current
	var camera by remember(graph.root) { mutableStateOf(FlowCanvasCamera()) }
	BoxWithConstraints(
		modifier = modifier
			.defaultMinSize(minWidth = layout.size.width.dp, minHeight = layout.size.height.dp)
			.clip(RoundedCornerShape(8.dp))
			.background(palette.background)
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val multiplier = if (change.scrollDelta.y < 0f) 1.1f else 0.9f
				camera = camera.zoomAt(
					cursorX = change.position.x,
					cursorY = change.position.y,
					scaleMultiplier = multiplier,
				)
				change.consume()
			},
	) {
		val viewportWidth = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
			with(density) { constraints.maxWidth.toDp().value }
		} else {
			layout.size.width
		}
		val viewportHeight = if (constraints.hasBoundedHeight && constraints.maxHeight > 0) {
			with(density) { constraints.maxHeight.toDp().value }
		} else {
			layout.size.height
		}
		val centerOffset = flowCanvasCenterOffset(
			viewport = flowCanvasViewportSize(layout.size, viewportWidth, viewportHeight),
			content = layout.size,
		)
		Canvas(Modifier.fillMaxSize()) {
			val scale = density.density
			drawGrid(palette, scale)
		}
		Box(
			modifier = Modifier
				.fillMaxSize()
				.pointerInput(layout, centerOffset, density.density) {
					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
						val startPoint = flowCanvasScreenToLayoutPoint(
							screenX = down.position.x,
							screenY = down.position.y,
							camera = camera,
							centerOffset = centerOffset,
							density = density.density,
						)
						if (flowCanvasHitTarget(layout, startPoint) != FlowCanvasHitTarget.Background) {
							return@awaitEachGesture
						}
						down.consume()
						while (true) {
							val event = awaitPointerEvent(PointerEventPass.Initial)
							val change = event.changes.firstOrNull { it.id == down.id } ?: break
							if (!change.pressed) {
								break
							}
							val dragAmount = change.positionChange()
							if (dragAmount != Offset.Zero) {
								camera = camera.pan(dragAmount.x, dragAmount.y)
								change.consume()
							}
						}
					}
				}
				.graphicsLayer {
					scaleX = camera.scale
					scaleY = camera.scale
					translationX = camera.offsetX + centerOffset.x * density.density
					translationY = camera.offsetY + centerOffset.y * density.density
					transformOrigin = TransformOrigin(0f, 0f)
				},
		) {
			Canvas(Modifier.fillMaxSize()) {
				val scale = density.density
				layout.edges.forEach { edge ->
					drawLine(
						color = palette.edge,
						start = edge.from.toOffset(scale),
						end = edge.to.toOffset(scale),
						strokeWidth = 1.2.dp.toPx(),
						cap = StrokeCap.Round,
					)
					if (edge.hasArrow) {
						drawArrowHead(edge.from.toOffset(scale), edge.to.toOffset(scale), palette, 7.dp.toPx())
					}
				}
			}
			TerminalPill(layout.start, palette)
			TerminalPill(layout.end, palette)
			layout.nodes.forEach { nodeLayout ->
				val node = graph.nodeMap[nodeLayout.nodeId] ?: return@forEach
				var dragOffset by remember(node.id, layout) { mutableStateOf(Offset.Zero) }
				FlowNodeCard(
					node = node,
					selected = graph.selectedNodeId == node.id,
					palette = palette,
					onSelect = onSelectNode,
					modifier = Modifier
						.offset {
							IntOffset(
								with(density) { nodeLayout.rect.x.dp.roundToPx() },
								with(density) { nodeLayout.rect.y.dp.roundToPx() },
							)
						}
						.size(
							width = nodeLayout.rect.width.dp,
							height = nodeLayout.rect.height.dp,
						)
						.graphicsLayer {
							translationX = dragOffset.x
							translationY = dragOffset.y
						}
						.pointerInput(node.id, layout) {
							detectDragGestures(
								onDragEnd = {
									nearestDropTarget(
										nodeId = node.id,
										center = nodeLayout.rect.center.toOffset(density.density) + dragOffset,
										layout = layout,
										scale = density.density,
									)?.let { target -> onMove(node.id, target) }
									dragOffset = Offset.Zero
								},
								onDragCancel = { dragOffset = Offset.Zero },
							) { change, dragAmount ->
								dragOffset += dragAmount
								change.consume()
							}
						},
				)
			}
			if (mode == FlowCanvasMode.Edit) {
				layout.insertPoints.forEach { point ->
					FlowInsertButton(
						point = point,
						palette = palette,
						onInsert = onInsert,
					)
				}
			}
		}
	}
}

@Composable
private fun TerminalPill(
	terminal: FlowCanvasTerminalLayout,
	palette: FlowCanvasPalette,
) {
	val density = LocalDensity.current
	Box(
		modifier = Modifier
			.offset {
				IntOffset(
					with(density) { terminal.rect.x.dp.roundToPx() },
					with(density) { terminal.rect.y.dp.roundToPx() },
				)
			}
			.size(terminal.rect.width.dp, terminal.rect.height.dp)
			.clip(RoundedCornerShape(999.dp))
			.background(palette.nodeBackground),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = terminal.label,
			color = palette.text,
			fontSize = 11.sp,
			fontWeight = FontWeight.Bold,
		)
	}
}

@Composable
private fun FlowInsertButton(
	point: FlowCanvasInsertPoint,
	palette: FlowCanvasPalette,
	onInsert: (FlowInsertTarget, FlowInsertKind) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	val density = LocalDensity.current
	Box(
		modifier = Modifier.offset {
			IntOffset(
				with(density) { (point.center.x - FlowInsertButtonDiameterDp / 2f).dp.roundToPx() },
				with(density) { (point.center.y - FlowInsertButtonDiameterDp / 2f).dp.roundToPx() },
			)
		},
	) {
		Box(
			modifier = Modifier
				.size(FlowInsertButtonDiameterDp.dp)
				.clip(CircleShape)
				.background(palette.insertBackground)
				.clickable { expanded = true },
			contentAlignment = Alignment.Center,
		) {
			Text("+", color = palette.selectedBorder, fontSize = 11.sp, fontWeight = FontWeight.Bold)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(
				text = { Text(flowInsertKindLabel(FlowInsertKind.Serial)) },
				onClick = {
					expanded = false
					onInsert(point.target, FlowInsertKind.Serial)
				},
			)
			DropdownMenuItem(
				text = { Text(flowInsertKindLabel(FlowInsertKind.Parallel)) },
				onClick = {
					expanded = false
					onInsert(point.target, FlowInsertKind.Parallel)
				},
			)
		}
	}
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
	palette: FlowCanvasPalette,
	scale: Float,
) {
	val step = 32f * scale
	val width = size.width
	val height = size.height
	var x = 0f
	while (x <= width) {
		drawLine(palette.grid, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
		x += step
	}
	var y = 0f
	while (y <= height) {
		drawLine(palette.grid, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
		y += step
	}
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
	from: Offset,
	to: Offset,
	palette: FlowCanvasPalette,
	size: Float,
) {
	val angle = atan2(to.y - from.y, to.x - from.x)
	val left = Offset(
		x = to.x - size * cos(angle - Math.PI.toFloat() / 6f),
		y = to.y - size * sin(angle - Math.PI.toFloat() / 6f),
	)
	val right = Offset(
		x = to.x - size * cos(angle + Math.PI.toFloat() / 6f),
		y = to.y - size * sin(angle + Math.PI.toFloat() / 6f),
	)
	val path = Path().apply {
		moveTo(to.x, to.y)
		lineTo(left.x, left.y)
		lineTo(right.x, right.y)
		close()
	}
	drawPath(path, palette.edge)
}

private fun FlowPoint.toOffset(scale: Float): Offset = Offset(x * scale, y * scale)

private fun FlowRect.contains(point: FlowPoint): Boolean =
	point.x >= x && point.x <= x + width && point.y >= y && point.y <= y + height

private fun FlowPoint.distanceSquared(other: FlowPoint): Float {
	val dx = x - other.x
	val dy = y - other.y
	return dx * dx + dy * dy
}

private fun nearestDropTarget(
	nodeId: String,
	center: Offset,
	layout: FlowCanvasLayoutResult,
	scale: Float,
): FlowDropTarget? =
	layout.nodes
		.filterNot { it.nodeId == nodeId }
		.minByOrNull { candidate ->
			val candidateCenter = candidate.rect.center.toOffset(scale)
			val dx = center.x - candidateCenter.x
			val dy = center.y - candidateCenter.y
			dx * dx + dy * dy
		}
		?.let { FlowDropTarget.AfterNode(it.nodeId) }
