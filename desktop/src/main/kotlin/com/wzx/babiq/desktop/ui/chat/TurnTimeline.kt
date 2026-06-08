package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.protocolJson
import com.wzx.babiq.desktop.state.ChatMessage
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

sealed interface TimelineItem {
	val id: String

	data class Message(
		val message: ChatMessage,
	) : TimelineItem {
		override val id: String = message.id
	}

	data class Process(
		override val id: String,
		val title: String,
		val expandedByDefault: Boolean,
		val rows: List<ProcessRow>,
	) : TimelineItem
}

data class ProcessRow(
	val id: String,
	val summary: String,
	val detail: String,
	val status: String,
	val kind: ProcessRowKind,
	val active: Boolean = false,
	val failed: Boolean = false,
)

enum class ProcessRowKind {
	Reasoning,
	Tool,
	File,
	Output,
}

/**
 * 将后端线性 item 流派生为更接近 DeepSeek-GUI 的 turn timeline。
 *
 * AppState.messages 仍保留原始顺序和 upsert 语义；这里仅在渲染前把同一轮里的推理、
 * 工具、文件读取和流式草稿折叠成一个“本轮工作过程”，让主聊天流优先展示用户输入和最终回答。
 */
fun deriveTurnTimeline(messages: List<ChatMessage>): List<TimelineItem> {
	val timeline = mutableListOf<TimelineItem>()
	val processRows = mutableListOf<ProcessRow>()
	var processOrdinal = 0

	fun flushProcess() {
		if (processRows.isEmpty()) {
			return
		}
		processOrdinal += 1
		val rows = processRows.toList()
		processRows.clear()
		val running = rows.any { it.active }
		val failed = rows.any { it.failed }
		timeline += TimelineItem.Process(
			id = "process-$processOrdinal-${rows.first().id}",
			title = if (running) "正在处理 · ${rows.size} 步" else "本轮工作过程 · ${rows.size} 步",
			expandedByDefault = running || failed,
			rows = rows,
		)
	}

	for (message in messages) {
		when (message) {
			is ChatMessage.User -> {
				flushProcess()
				timeline += TimelineItem.Message(message)
			}
			is ChatMessage.Agent -> {
				if (message.streaming) {
					processRows += ProcessRow(
						id = message.id,
						summary = "输出草稿",
						detail = message.text,
						status = "running",
						kind = ProcessRowKind.Output,
						active = true,
					)
				} else {
					flushProcess()
					timeline += TimelineItem.Message(message)
				}
			}
			is ChatMessage.Reasoning -> {
				processRows += ProcessRow(
					id = message.id,
					summary = "推理",
					detail = message.text,
					status = if (message.completed) "completed" else "running",
					kind = ProcessRowKind.Reasoning,
					active = !message.completed,
				)
			}
			is ChatMessage.Tool -> {
				processRows += ProcessRow(
					id = message.id,
					summary = summarizeTool(message.title),
					detail = cleanToolDetail(message.detail),
					status = message.status,
					kind = ProcessRowKind.Tool,
					active = message.status.isActiveStatus(),
					failed = message.status.isFailedStatus(),
				)
			}
			is ChatMessage.FileChange -> {
				processRows += ProcessRow(
					id = message.id,
					summary = summarizeFileChange(message),
					detail = message.preview.orEmpty(),
					status = message.status,
					kind = ProcessRowKind.File,
					active = message.status.isActiveStatus(),
					failed = message.status.isFailedStatus(),
				)
			}
			is ChatMessage.TurnSummary -> {
				flushProcess()
				timeline += TimelineItem.Message(message)
			}
		}
	}
	flushProcess()
	return timeline
}

@Composable
fun TimelineProcessCard(item: TimelineItem.Process) {
	var expanded by remember(item.id, item.expandedByDefault) { mutableStateOf(item.expandedByDefault) }
	Row(modifier = Modifier.fillMaxWidth()) {
		Column(
			modifier = Modifier
				.widthIn(max = 680.dp)
				.background(Color(0xFFF6F7F9), RoundedCornerShape(10.dp))
				.border(1.dp, Color(0xFFE2E5EA), RoundedCornerShape(10.dp))
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable { expanded = !expanded },
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					item.title,
					style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
					color = Color(0xFF596070),
				)
				Text(
					if (expanded) "收起" else "展开",
					style = MaterialTheme.typography.labelSmall,
					color = BaBiQColors.Muted,
				)
			}
			if (expanded) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					item.rows.forEach { row -> ProcessRowView(row) }
				}
			}
		}
	}
}

@Composable
private fun ProcessRowView(row: ProcessRow) {
	val tone = when {
		row.failed -> Color(0xFFB42318)
		row.active -> BaBiQColors.Accent
		else -> Color(0xFF4F5665)
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
			.background(BaBiQColors.Panel, RoundedCornerShape(8.dp))
			.padding(10.dp),
		verticalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
			Text(
				row.summary,
				style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
				color = tone,
			)
			Text(
				statusLabel(row.status),
				style = MaterialTheme.typography.labelSmall,
				color = BaBiQColors.Muted,
			)
		}
		if (row.detail.isNotBlank()) {
			SelectionContainer {
				Text(
					row.detail,
					style = MaterialTheme.typography.bodySmall.copy(
						fontFamily = if (row.kind == ProcessRowKind.Tool || row.kind == ProcessRowKind.File) {
							FontFamily.Monospace
						} else {
							FontFamily.Default
						},
					),
					color = Color(0xFF555B66),
				)
			}
		}
	}
}

private fun summarizeTool(title: String): String {
	val normalized = title.trim()
	val lower = normalized.lowercase(Locale.ROOT)
	val path = extractArgument(normalized, "path")
	val command = extractArgument(normalized, "command")
	return when {
		lower.startsWith("read_file") -> listOfNotNull("读取文件", path).joinToString(" ")
		lower.startsWith("list_dir") -> listOfNotNull("列出目录", path).joinToString(" ")
		lower.startsWith("write_file") -> listOfNotNull("写入文件", path).joinToString(" ")
		lower.startsWith("edit_file") -> listOfNotNull("编辑文件", path).joinToString(" ")
		lower.startsWith("apply_patch") -> "应用补丁"
		lower.startsWith("exec_shell") -> listOfNotNull("执行命令", command).joinToString(" ")
		lower.startsWith("grep") -> listOfNotNull("搜索内容", path).joinToString(" ")
		lower.startsWith("上下文压缩") -> "上下文压缩"
		lower.startsWith("子 agent") -> normalized
		else -> normalized
	}
}

private fun summarizeFileChange(message: ChatMessage.FileChange): String {
	val actionLabel = when (message.action.lowercase(Locale.ROOT)) {
		"read" -> "读取文件"
		"write", "create" -> "写入文件"
		"edit" -> "编辑文件"
		"patch" -> "应用补丁"
		"delete" -> "删除文件"
		else -> message.action
	}
	return "$actionLabel ${message.path}".trim()
}

private val untrustedDataBlock = Regex("""(?s)<untrusted-data\b[^>]*>(.*?)</untrusted-data>""")

private fun cleanToolDetail(detail: String): String {
	val unwrapped = untrustedDataBlock.replace(detail) { it.groupValues[1] }.trim()
	if (unwrapped.isBlank()) {
		return ""
	}
	return renderJsonToolDetail(unwrapped) ?: unwrapped
}

private fun renderJsonToolDetail(text: String): String? {
	val element = runCatching { protocolJson.parseToJsonElement(text) }.getOrNull() ?: return null
	return when (element) {
		is JsonObject -> renderJsonObjectToolDetail(element)
		is JsonArray -> element.joinToString("\n") { renderJsonValue(it) }.ifBlank { "[]" }
		is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() } ?: text
	}
}

private fun renderJsonObjectToolDetail(json: JsonObject): String? {
	json.nonBlankString("error")?.let { return "错误：$it" }
	json.nonBlankString("output")?.let { return it }
	return when (json["ok"]?.jsonPrimitive?.booleanOrNull) {
		true -> "执行成功"
		false -> "执行失败"
		null -> null
	}
}

private fun renderJsonValue(element: kotlinx.serialization.json.JsonElement): String =
	when (element) {
		is JsonObject -> renderJsonObjectToolDetail(element)
			?: element.entries.joinToString("\n") { "${it.key}: ${renderJsonValue(it.value)}" }
		is JsonArray -> element.joinToString("\n") { renderJsonValue(it) }
		is JsonPrimitive -> element.contentOrNull ?: element.toString()
	}

private fun JsonObject.nonBlankString(name: String): String? =
	this[name]?.jsonPrimitive?.contentOrNull
		?.trim()
		?.takeIf { it.isNotBlank() }

private fun extractArgument(text: String, name: String): String? {
	val marker = "$name="
	val start = text.indexOf(marker)
	if (start < 0) return null
	val valueStart = start + marker.length
	val nextMarker = Regex("""\s[a-zA-Z_][a-zA-Z0-9_]*=""").find(text, valueStart)
	val valueEnd = nextMarker?.range?.first ?: text.length
	return text.substring(valueStart, valueEnd)
		.trim()
		.trim('"')
		.ifBlank { null }
}

private fun String.isActiveStatus(): Boolean =
	lowercase(Locale.ROOT) in setOf("running", "pending", "in_progress", "started", "streaming")

private fun String.isFailedStatus(): Boolean =
	lowercase(Locale.ROOT) in setOf("failed", "error", "canceled", "cancelled")

private fun statusLabel(status: String): String =
	when (status.lowercase(Locale.ROOT)) {
		"running" -> "运行中"
		"pending" -> "等待中"
		"in_progress" -> "处理中"
		"completed", "success", "succeeded" -> "已完成"
		"failed", "error" -> "失败"
		"canceled", "cancelled" -> "已取消"
		else -> status
	}
