package com.wzx.babiq.desktop.flowcanvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class FlowNodeCardStyle(
	val borderColor: Color,
	val statusColor: Color,
	val statusText: String,
	val roleColor: Color,
	val errorSummary: String?,
)

fun flowNodeCardStyle(
	node: FlowNode,
	selected: Boolean,
	palette: FlowCanvasPalette,
): FlowNodeCardStyle {
	val statusBorder = when (node.status) {
		FlowNodeStatus.Failed -> palette.failed
		else -> null
	}
	return FlowNodeCardStyle(
		borderColor = statusBorder ?: if (selected) palette.selectedBorder else palette.nodeBorder,
		statusColor = statusColor(node.status, palette),
		statusText = statusText(node.status),
		roleColor = roleColor(node, palette),
		errorSummary = node.errorSummary?.takeIf { node.status == FlowNodeStatus.Failed && it.isNotBlank() },
	)
}

@Composable
fun FlowNodeCard(
	node: FlowNode,
	selected: Boolean,
	palette: FlowCanvasPalette,
	modifier: Modifier = Modifier,
	onSelect: (String) -> Unit = {},
) {
	val style = flowNodeCardStyle(node, selected, palette)
	Column(
		modifier = modifier
			.clip(RoundedCornerShape(6.dp))
			.background(palette.nodeBackground)
			.border(
				width = 1.dp,
				color = style.borderColor,
				shape = RoundedCornerShape(6.dp),
			)
			.clickable { onSelect(node.id) }
			.padding(horizontal = 10.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.weight(1f),
			) {
				Box(
					modifier = Modifier
						.size(8.dp)
						.background(style.roleColor, CircleShape),
				)
				Text(
					text = node.title,
					color = palette.text,
					fontSize = 12.sp,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Spacer(Modifier.size(4.dp))
			Text(
				text = style.statusText,
				color = style.statusColor,
				fontSize = 10.sp,
				maxLines = 1,
			)
		}
		Text(
			text = "${node.role} / ${flowNodeModeLabel(node.mode)}",
			color = palette.muted,
			fontSize = 10.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = node.task,
			color = palette.text,
			fontSize = 11.sp,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		style.errorSummary?.let { summary ->
			Text(
				text = summary,
				color = palette.failed,
				fontSize = 10.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

private fun roleColor(node: FlowNode, palette: FlowCanvasPalette): Color =
	when (node.mode) {
		FlowNodeMode.WorkspaceTool -> palette.workspaceDot
		else -> palette.readOnlyDot
	}

private fun statusColor(status: FlowNodeStatus, palette: FlowCanvasPalette): Color =
	when (status) {
		FlowNodeStatus.Running -> palette.running
		FlowNodeStatus.Completed -> palette.completed
		FlowNodeStatus.Failed -> palette.failed
		else -> palette.muted
	}

private fun statusText(status: FlowNodeStatus): String =
	when (status) {
		FlowNodeStatus.Pending -> ""
		FlowNodeStatus.Running -> "RUN"
		FlowNodeStatus.Completed -> "OK"
		FlowNodeStatus.Failed -> "ERR"
		FlowNodeStatus.Canceled -> "STOP"
	}

internal fun flowNodeModeLabel(mode: FlowNodeMode): String =
	when (mode) {
		FlowNodeMode.ReadOnlyTool -> "只读"
		FlowNodeMode.WorkspaceTool -> "全工具"
		FlowNodeMode.Goal -> "目标"
		FlowNodeMode.End -> "结束"
	}
