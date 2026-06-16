package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.protocol.FlowStructureDto
import com.wzx.babiq.desktop.protocol.WorkUnitConfiguration
import com.wzx.babiq.desktop.protocol.WorkUnitGoalInfo
import com.wzx.babiq.desktop.protocol.WorkUnitInfo
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.WorkUnitUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class WorkUnitSectionModel(
	val visible: Boolean,
	val rows: List<WorkUnitRowModel>,
	val selectedDetail: WorkUnitDetailModel? = null,
)

data class WorkUnitRowModel(
	val workUnitId: String,
	val kindLabel: String,
	val name: String,
	val runtimeStateLabel: String,
	val statusLabel: String,
	val activeGoal: String,
	val goalCountText: String,
	val removable: Boolean,
	val removeBlockedLabel: String? = null,
	val detailActionLabel: String,
	val startActionLabel: String?,
)

data class WorkUnitDetailModel(
	val workUnitId: String,
	val name: String,
	val title: String,
	val cwd: String,
	val sandboxLabel: String,
	val statusLabel: String,
	val modelLabel: String,
	val startActionLabel: String?,
	val removeActionLabel: String?,
	val editableGoalId: String?,
	val editableGoalText: String?,
	val configuration: WorkUnitConfiguration? = null,
	val configJson: String? = null,
	val structure: FlowStructureDto? = null,
	val structureJson: String? = null,
	val goals: List<WorkUnitGoalRowModel>,
	val completedRuns: List<WorkUnitCompletedRunModel> = emptyList(),
)

data class WorkUnitGoalRowModel(
	val goalId: String,
	val label: String,
)

data class WorkUnitCompletedRunModel(
	val goalId: String,
	val title: String,
	val summary: String,
	val detail: String,
	val completedAt: String?,
	val completedAtLabel: String? = completedAt,
)

private val WorkUnitCompletedTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

fun buildWorkUnitSectionModel(
	state: WorkUnitUiState,
	kindFilter: String? = null,
): WorkUnitSectionModel {
	val rows = state.items
		.filterNot { it.removed || it.status.equals("removed", ignoreCase = true) }
		.filter { item -> kindFilter.isNullOrBlank() || item.kind.equals(kindFilter, ignoreCase = true) }
		.map(::toRowModel)
	return WorkUnitSectionModel(
		visible = rows.isNotEmpty(),
		rows = rows,
		selectedDetail = null,
	)
}

@Composable
fun WorkUnitSection(
	state: WorkUnitUiState,
	kindFilter: String? = null,
	onSelect: (String) -> Unit = {},
	onConfigure: (String) -> Unit = {},
	onStart: (String) -> Unit = {},
	onRemove: (String) -> Unit = {},
	onUpdateGoal: (String, String, String) -> Unit = { _, _, _ -> },
) {
	val model = buildWorkUnitSectionModel(state, kindFilter)
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
			WorkUnitRow(row = row, onSelect = onSelect, onConfigure = onConfigure, onStart = onStart, onRemove = onRemove)
		}
	}
}

@Composable
private fun WorkUnitRow(
	row: WorkUnitRowModel,
	onSelect: (String) -> Unit,
	onConfigure: (String) -> Unit,
	onStart: (String) -> Unit,
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
					Text("${row.runtimeStateLabel} · ${row.statusLabel} · ${row.goalCountText}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
					TextButton(onClick = { onConfigure(row.workUnitId) }) { Text(row.detailActionLabel) }
					row.startActionLabel?.let { label ->
						TextButton(onClick = { onStart(row.workUnitId) }) { Text(label) }
					}
					if (row.removable) {
						TextButton(onClick = { onRemove(row.workUnitId) }) { Text("移除") }
					} else {
						row.removeBlockedLabel?.let { label ->
							Text(label, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
						}
					}
				}
			}
			Text(row.activeGoal, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
	}
}

@Composable
fun WorkUnitConfigCard(
	detail: WorkUnitDetailModel,
	onStart: (String) -> Unit,
	onUpdateGoal: (String, String, String) -> Unit,
	onRemove: (String) -> Unit = {},
) {
	var draftGoal by remember(detail.editableGoalId, detail.editableGoalText) {
		mutableStateOf(detail.editableGoalText ?: "")
	}
	Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(detail.title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
		Text("状态: ${detail.statusLabel}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text("目录: ${detail.cwd}", style = MaterialTheme.typography.labelSmall)
		Text("权限: ${detail.sandboxLabel}", style = MaterialTheme.typography.labelSmall)
		Text("模型: ${detail.modelLabel}", style = MaterialTheme.typography.labelSmall)
		detail.editableGoalId?.let { goalId ->
			OutlinedTextField(
				value = draftGoal,
				onValueChange = { draftGoal = it },
				label = { Text("当前待执行目标") },
				modifier = Modifier.fillMaxWidth(),
				minLines = 3,
				maxLines = 6,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				Button(
					onClick = { onUpdateGoal(detail.workUnitId, goalId, draftGoal) },
					enabled = draftGoal.isNotBlank() && draftGoal != (detail.editableGoalText ?: ""),
				) {
					Text("保存目标")
				}
				detail.startActionLabel?.let { label ->
					Button(onClick = { onStart(detail.workUnitId) }) { Text(label) }
				}
				detail.removeActionLabel?.let { label ->
					TextButton(onClick = { onRemove(detail.workUnitId) }) { Text(label) }
				}
			}
		}
		if (detail.editableGoalId == null && (detail.startActionLabel != null || detail.removeActionLabel != null)) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				detail.startActionLabel?.let { label ->
					Button(onClick = { onStart(detail.workUnitId) }) { Text(label) }
				}
				detail.removeActionLabel?.let { label ->
					TextButton(onClick = { onRemove(detail.workUnitId) }) { Text(label) }
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

fun workUnitDetailModel(info: WorkUnitInfo, modelLabel: String): WorkUnitDetailModel =
	WorkUnitDetailModel(
		workUnitId = info.workUnitId,
		name = info.name,
		title = "${kindLabel(info.kind)} · ${info.name}",
		cwd = info.cwd?.takeIf { it.isNotBlank() } ?: "未记录",
		sandboxLabel = sandboxLabel(info.sandboxMode),
		statusLabel = statusLabel(info.status),
		modelLabel = modelLabel.ifBlank { "未选择模型" },
		startActionLabel = startActionLabel(info),
		removeActionLabel = removeActionLabel(info.status, info.removed),
		editableGoalId = editableGoal(info)?.goalId,
		editableGoalText = editableGoal(info)?.goalText,
		configuration = info.configuration,
		configJson = info.configJson,
		structure = info.structure,
		structureJson = info.structureJson,
		goals = info.goals.map(::toGoalRowModel),
		completedRuns = info.goals
			.filter {
				it.status.equals("completed", ignoreCase = true) &&
					!it.summary.isNullOrBlank() &&
					it.matchesWorkUnitRunRef(info.kind)
			}
			.sortedByDescending(::completedRunSortKey)
			.map(::toCompletedRunModel),
	)

private fun toRowModel(item: ThreadItem.WorkUnit): WorkUnitRowModel =
	WorkUnitRowModel(
		workUnitId = item.workUnitId,
		kindLabel = kindLabel(item.kind),
		name = item.name,
		runtimeStateLabel = runtimeStateLabel(item.status),
		statusLabel = statusLabel(item.status),
		activeGoal = item.currentGoal?.takeIf { it.isNotBlank() } ?: "暂无待执行目标",
		goalCountText = "${item.goalCount} 个目标",
		removable = !item.status.equals("running", ignoreCase = true),
		removeBlockedLabel = if (item.status.equals("running", ignoreCase = true)) "运行中不可移除" else null,
		detailActionLabel = detailActionLabel(item),
		startActionLabel = null,
	)

private fun editableGoal(info: WorkUnitInfo): WorkUnitGoalInfo? =
	info.goals.firstOrNull { goal ->
		goal.goalId == info.currentGoalId && goal.status.equals("pending", ignoreCase = true)
	} ?: info.goals.lastOrNull { goal -> goal.status.equals("pending", ignoreCase = true) }

private fun toGoalRowModel(goal: WorkUnitGoalInfo): WorkUnitGoalRowModel =
	WorkUnitGoalRowModel(
		goalId = goal.goalId,
		label = "${goalStatusLabel(goal.status)} · ${goal.goalText}",
	)

private fun toCompletedRunModel(goal: WorkUnitGoalInfo): WorkUnitCompletedRunModel =
	WorkUnitCompletedRunModel(
		goalId = goal.goalId,
		title = goal.goalText.takeIf { it.isNotBlank() } ?: goal.runRefId ?: goal.goalId,
		summary = compactCompletedRunSummary(goal.summary, goal.goalText),
		detail = completedRunDetail(goal.summary, goal.goalText),
		completedAt = goal.completedAt,
		completedAtLabel = formatWorkUnitCompletedAt(goal.completedAt),
	)

private fun completedRunSortKey(goal: WorkUnitGoalInfo): String =
	goal.completedAt ?: goal.startedAt ?: goal.createdAt ?: ""

private fun compactCompletedRunSummary(summary: String?, fallback: String): String {
	val text = summary.orEmpty().trim()
	if (text.isBlank()) {
		return fallback
	}
	val extracted = extractCompletedOutputTexts(text, limit = 180)
	if (extracted.isNotEmpty()) {
		return extracted.joinToString("\n\n")
	}
	return compactPlainSummary(text)
}

private fun completedRunDetail(summary: String?, fallback: String): String {
	val text = summary.orEmpty().trim()
	if (text.isBlank()) {
		return fallback
	}
	val extracted = extractCompletedOutputTexts(text, limit = 720)
	if (extracted.isNotEmpty()) {
		return extracted.joinToString("\n\n")
	}
	return compactPlainSummary(text, limit = 1200)
}

private fun extractCompletedOutputTexts(summary: String, limit: Int): List<String> {
	val root = try {
		protocolJson.parseToJsonElement(summary)
	} catch (_: SerializationException) {
		return emptyList()
	} catch (_: IllegalArgumentException) {
		return emptyList()
	}
	val texts = linkedSetOf<String>()
	root.asObjectOrNull()?.get("text")?.asStringOrNull()?.let { texts += compactPlainSummary(it) }
	val data = root.asObjectOrNull()
		?.get("OverAllState")
		?.asObjectOrNull()
		?.get("data")
		?.asObjectOrNull()
		?: root.asObjectOrNull()?.get("data")?.asObjectOrNull()
		?: return texts.toList()
	data.entries
		.filter { it.key.endsWith("_output") }
		.mapNotNull { (_, value) ->
			value.asStringOrNull()
				?: value.asObjectOrNull()?.get("text")?.asStringOrNull()
				?: value.asObjectOrNull()?.get("output")?.asStringOrNull()
		}
		.map { compactPlainSummary(it, limit = limit) }
		.filter { it.isNotBlank() }
		.forEach { texts += it }
	return texts.toList()
}

private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.asStringOrNull(): String? =
	(this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private fun compactPlainSummary(summary: String, limit: Int = 220): String {
	val compact = summary
		.lineSequence()
		.map { it.trim() }
		.filter { it.isNotBlank() }
		.joinToString(" ")
		.replace(Regex("\\s+"), " ")
		.trim()
	return if (compact.length <= limit) compact else compact.take(limit - 3).trimEnd() + "..."
}

private fun WorkUnitGoalInfo.matchesWorkUnitRunRef(workUnitKind: String): Boolean {
	val expected = when (workUnitKind.lowercase()) {
		"orchestration" -> "orchestration"
		"team" -> "team"
		else -> null
	} ?: return true
	runRefType?.takeIf { it.isNotBlank() }?.let { return it.equals(expected, ignoreCase = true) }
	runRefId?.takeIf { it.isNotBlank() }?.let { refId ->
		return when {
			refId.startsWith("orch_", ignoreCase = true) -> expected == "orchestration"
			refId.startsWith("team_", ignoreCase = true) -> expected == "team"
			else -> true
		}
	}
	return true
}

internal fun formatWorkUnitCompletedAt(value: String?): String? {
	val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
	return runCatching {
		Instant.parse(text).atZone(ZoneId.systemDefault()).format(WorkUnitCompletedTimeFormatter)
	}.recoverCatching {
		OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).format(WorkUnitCompletedTimeFormatter)
	}.recoverCatching {
		LocalDateTime.parse(text).format(WorkUnitCompletedTimeFormatter)
	}.getOrElse {
		text.take(16).replace("T", " ")
	}
}

private fun startActionLabel(info: WorkUnitInfo): String? =
	when {
		info.status.equals("running", ignoreCase = true) ||
			info.status.equals("removed", ignoreCase = true) -> null
		info.goals.any { it.status.equals("pending", ignoreCase = true) } -> "开始执行"
		info.status.equals("failed", ignoreCase = true) && info.goals.isNotEmpty() -> "重新执行"
		info.status.equals("completed", ignoreCase = true) && info.goals.isNotEmpty() -> "重新执行"
		else -> null
	}

private fun removeActionLabel(status: String, removed: Boolean): String? =
	if (removed || status.equals("running", ignoreCase = true) || status.equals("removed", ignoreCase = true)) {
		null
	} else {
		"移除"
	}

private fun detailActionLabel(item: ThreadItem.WorkUnit): String {
	val verb = if (item.status.equals("running", ignoreCase = true) ||
		item.status.equals("completed", ignoreCase = true) ||
		item.status.equals("failed", ignoreCase = true)
	) {
		"查看"
	} else {
		"配置"
	}
	return verb + kindLabel(item.kind)
}

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

private fun runtimeStateLabel(status: String): String =
	if (status.equals("running", ignoreCase = true)) {
		"运行中"
	} else {
		"空闲中"
	}

private fun goalStatusLabel(status: String): String =
	when (status.lowercase()) {
		"pending" -> "待执行"
		"running" -> "运行中"
		"completed" -> "已完成"
		"failed" -> "失败"
		else -> status
	}

private fun sandboxLabel(mode: String?): String =
	when (mode?.uppercase()) {
		"READ_ONLY" -> "只读"
		"WORKSPACE_WRITE" -> "工作区可写"
		"FULL_ACCESS", "DANGER_FULL_ACCESS" -> "完全访问权限"
		null, "" -> "未记录"
		else -> mode
	}
