package com.wzx.babiq.desktop.ui.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Dialog
import com.wzx.babiq.desktop.state.PendingApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 工具审批弹窗。
 *
 * 后端通过 `approval/request` 暂停 turn 后，桌面端把参数展示给用户；用户选择 approve/deny/edit 后，
 * Controller 会调用 `approval/respond` 让后端继续或终止执行。
 */
@Composable
fun ApprovalDialog(
	approval: PendingApproval?,
	canSubmit: Boolean,
	onDismiss: () -> Unit,
	onDecision: (String, String?) -> Unit,
) {
	// 没有待审批请求时不渲染弹窗，这是 Compose 里最直接的条件 UI 写法。
	if (approval == null) {
		return
	}

	// editedArgs 是用户可编辑的参数副本，不直接修改 pendingApproval，避免状态源被 UI 临时输入污染。
	var editedArgs by remember { mutableStateOf(approval.arguments) }
	LaunchedEffect(approval.itemId) {
		// 同一个弹窗组件会被 Compose 复用；itemId 变化时重置编辑框，避免上一次审批参数残留。
		editedArgs = approval.arguments
	}

	// 默认只展示短摘要，避免把大段 JSON 和 HTML 直接塞进弹窗；需要精修参数时再展开编辑区。
	var editing by remember(approval.itemId) { mutableStateOf(false) }
	val argumentRows = approvalArgumentRows(approval.arguments)

	Dialog(onDismissRequest = onDismiss) {
		Surface(
			shape = RoundedCornerShape(18.dp),
			tonalElevation = 6.dp,
			color = MaterialTheme.colorScheme.surface,
			modifier = Modifier.widthIn(min = 360.dp, max = 520.dp),
		) {
			Column(
				modifier = Modifier.padding(24.dp),
				verticalArrangement = Arrangement.spacedBy(14.dp),
			) {
				Text(
					text = "有一个工具需要你确认",
					style = MaterialTheme.typography.titleLarge,
				)
				Text(
					text = "BaBiQ 想执行下面的工具。确认后才会继续。",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				HorizontalDivider()
				ApprovalInfoRow("工具", approvalToolLabel(approval.toolName), approval.toolName)
				if (approval.description.isNotBlank()) {
					ApprovalInfoRow("说明", approval.description, null)
				}
				argumentRows.forEach { (name, value) ->
					ApprovalInfoRow(name, value, null)
				}
				if (!canSubmit) {
					Text(
						text = "连接恢复后才能提交审批",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				if (editing) {
					OutlinedTextField(
						value = editedArgs,
						onValueChange = { editedArgs = it },
						label = { Text("参数 JSON") },
						textStyle = MaterialTheme.typography.bodySmall.copy(
							fontFamily = FontFamily.Monospace,
						),
						modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
						minLines = 5,
					)
				}
				Text(
					text = "始终允许仅对当前会话、同工具和同参数生效。",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.End,
				) {
					TextButton(enabled = canSubmit, onClick = { onDecision("deny", null) }) {
						Text("拒绝")
					}
					TextButton(enabled = canSubmit, onClick = { onDecision("always", null) }) {
						Text("始终允许")
					}
					TextButton(onClick = { editing = !editing }) {
						Text(if (editing) "收起参数" else "编辑参数")
					}
					Button(
						enabled = canSubmit,
						onClick = {
							// 展开编辑区后，主按钮提交 edit；未展开时提交 approve，避免误把原参数当成用户修改。
							if (editing) {
								onDecision("edit", editedArgs)
							} else {
								onDecision("approve", null)
							}
						},
					) {
						Text(if (editing) "修改后批准" else "批准")
					}
				}
			}
		}
	}
}

/**
 * 审批信息行。
 *
 * label 是用户看到的字段名，value 是主要内容；rawValue 只在工具名这种需要同时保留协议名时展示。
 */
@Composable
private fun ApprovalInfoRow(label: String, value: String, rawValue: String?) {
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = if (rawValue == null || rawValue == value) value else "$value · $rawValue",
			style = MaterialTheme.typography.bodyMedium,
		)
	}
}

/**
 * 把协议工具名转成审批弹窗里的中文短标签。
 *
 * 未收录的工具保持原始协议名，方便开发阶段定位后端实际发来的 toolName。
 */
internal fun approvalToolLabel(toolName: String): String = when (toolName) {
	"write_file" -> "写入文件"
	"exec_shell" -> "执行命令"
	"apply_patch" -> "修改文件"
	else -> toolName
}

/**
 * 从工具参数 JSON 中抽取最值得用户确认的字段。
 *
 * 这里优先展示 path/command/content/cwd 这类高风险字段，避免把完整 HTML、补丁或命令参数塞满弹窗。
 */
internal fun approvalArgumentRows(arguments: String): List<Pair<String, String>> {
	val jsonObject = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
		?: return fallbackArgumentRows(arguments)
	val priorityKeys = listOf("path", "command", "content", "cwd")
	return priorityKeys
		.mapNotNull { key -> jsonObject[key]?.let { key to compactJsonValue(it) } }
		.take(2)
}

private fun fallbackArgumentRows(arguments: String): List<Pair<String, String>> {
	val compact = arguments.replace(Regex("\\s+"), " ").trim()
	return if (compact.isBlank()) emptyList() else listOf("参数" to compact.take(96))
}

private fun compactJsonValue(value: JsonElement): String {
	val raw = when (value) {
		is JsonPrimitive -> value.contentOrNull ?: value.toString()
		else -> value.toString()
	}
	return raw.replace(Regex("\\s+"), " ").trim().take(96)
}
