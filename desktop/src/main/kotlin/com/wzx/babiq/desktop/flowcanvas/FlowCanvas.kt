package com.wzx.babiq.desktop.flowcanvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun FlowCanvas(
	graph: FlowGraph,
	modifier: Modifier = Modifier,
	mode: FlowCanvasMode = FlowCanvasMode.Edit,
	palette: FlowCanvasPalette = FlowCanvasPalette(),
	layoutConfig: FlowCanvasLayoutConfig = FlowCanvasLayoutConfig(),
	onSelectNode: (String) -> Unit = {},
	onInsert: (String?, FlowInsertKind) -> Unit = { _, _ -> },
) {
	val layout = remember(graph, layoutConfig) { layoutFlowCanvas(graph, layoutConfig) }
	val density = LocalDensity.current
	var camera by remember(graph.root) { mutableStateOf(FlowCanvasCamera()) }
	Box(
		modifier = modifier
			.size(
				width = layout.size.width.dp,
				height = layout.size.height.dp,
			)
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
			}
			.pointerInput(Unit) {
				detectDragGestures { change, dragAmount ->
					camera = camera.pan(dragAmount.x, dragAmount.y)
					change.consume()
				}
			},
	) {
		Box(
			modifier = Modifier
				.matchParentSize()
				.graphicsLayer {
					scaleX = camera.scale
					scaleY = camera.scale
					translationX = camera.offsetX
					translationY = camera.offsetY
					transformOrigin = TransformOrigin(0f, 0f)
				},
		) {
			Canvas(Modifier.matchParentSize()) {
				val scale = density.density
				drawGrid(layout.size, palette, scale)
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
						),
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
	onInsert: (String?, FlowInsertKind) -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	val density = LocalDensity.current
	Box(
		modifier = Modifier.offset {
			IntOffset(
				with(density) { (point.center.x - 14f).dp.roundToPx() },
				with(density) { (point.center.y - 14f).dp.roundToPx() },
			)
		},
	) {
		TextButton(
			onClick = { expanded = true },
			modifier = Modifier
				.size(28.dp)
				.clip(RoundedCornerShape(999.dp))
				.background(palette.insertBackground),
		) {
			Text("+", color = palette.selectedBorder, fontWeight = FontWeight.Bold)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(
				text = { Text("Serial node") },
				onClick = {
					expanded = false
					onInsert(point.anchorNodeId, FlowInsertKind.Serial)
				},
			)
			DropdownMenuItem(
				text = { Text("Parallel node") },
				onClick = {
					expanded = false
					onInsert(point.anchorNodeId, FlowInsertKind.Parallel)
				},
			)
			DropdownMenuItem(
				text = { Text("Routing branch") },
				onClick = {
					expanded = false
					onInsert(point.anchorNodeId, FlowInsertKind.Routing)
				},
			)
		}
	}
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(
	size: FlowSize,
	palette: FlowCanvasPalette,
	scale: Float,
) {
	val step = 32f * scale
	val width = size.width * scale
	val height = size.height * scale
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
