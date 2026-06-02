package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.WorkUnitUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

data class WorkUnitSectionModel(
	val visible: Boolean,
	val rows: List<WorkUnitRowModel>,
)

data class WorkUnitRowModel(
	val workUnitId: String,
	val kindLabel: String,
	val name: String,
	val statusLabel: String,
	val activeGoal: String,
	val goalCountText: String,
	val removable: Boolean,
)

fun buildWorkUnitSectionModel(state: WorkUnitUiState): WorkUnitSectionModel {
	val rows = state.items
		.filterNot { it.removed || it.status.equals("removed", ignoreCase = true) }
		.map(::toRowModel)
	return WorkUnitSectionModel(visible = rows.isNotEmpty(), rows = rows)
}

@Composable
fun WorkUnitSection(
	state: WorkUnitUiState,
	onRemove: (String) -> Unit = {},
) {
	val model = buildWorkUnitSectionModel(state)
	if (!model.visible) {
		return
	}
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text("工作容器", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			if (state.loading) {
				Text("同步中", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			}
		}
		state.error?.let { error ->
			Text(error, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Danger)
		}
		model.rows.forEach { row ->
			WorkUnitRow(row = row, onRemove = onRemove)
		}
	}
}

@Composable
private fun WorkUnitRow(
	row: WorkUnitRowModel,
	onRemove: (String) -> Unit,
) {
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
				Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
					Text("${row.kindLabel} · ${row.name}", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
					Text("${row.statusLabel} · ${row.goalCountText}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
				}
				if (row.removable) {
					TextButton(onClick = { onRemove(row.workUnitId) }) { Text("移除") }
				}
			}
			Text(row.activeGoal, style = MaterialTheme.typography.bodySmall)
		}
	}
}

private fun toRowModel(item: ThreadItem.WorkUnit): WorkUnitRowModel =
	WorkUnitRowModel(
		workUnitId = item.workUnitId,
		kindLabel = kindLabel(item.kind),
		name = item.name,
		statusLabel = statusLabel(item.status),
		activeGoal = item.currentGoal?.takeIf { it.isNotBlank() } ?: "暂无待执行目标",
		goalCountText = "${item.goalCount} 个目标",
		removable = !item.status.equals("running", ignoreCase = true),
	)

private fun kindLabel(kind: String): String =
	when (kind.lowercase()) {
		"orchestration" -> "编排"
		"team" -> "团队"
		else -> kind
	}

private fun statusLabel(status: String): String =
	when (status.lowercase()) {
		"idle", "pending", "waiting_config" -> "待配置"
		"running" -> "运行中"
		"completed" -> "已完成"
		"failed" -> "失败"
		"removed" -> "已移除"
		else -> status
	}
