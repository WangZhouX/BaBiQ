package com.wzx.babiq.desktop.ui.approval

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import com.wzx.babiq.desktop.state.PendingApproval

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

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("需要审批工具执行") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text("工具: ${approval.toolName}")
				Text(approval.description)
				OutlinedTextField(
					value = editedArgs,
					onValueChange = { editedArgs = it },
					label = { Text("参数 / 命令") },
					textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
						fontFamily = FontFamily.Monospace,
					),
					modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
					minLines = 5,
				)
				if (!canSubmit) {
					Text("连接恢复后才能提交审批")
				}
				Text(
					text = "始终允许：后端暂未开放 always 决策，P1-4 保持禁用。",
					style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
					color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			// 批准和修改后批准都会让后端恢复 turn，只是 edit 会携带用户修改后的参数。
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(enabled = canSubmit, onClick = { onDecision("approve", null) }) {
					Text("批准")
				}
				Button(enabled = canSubmit, onClick = { onDecision("edit", editedArgs) }) {
					Text("修改后批准")
				}
			}
		},
		dismissButton = {
			// P1-4 后端只承诺 approve/deny/edit；Always 先禁用，避免 UI 暗示不存在的协议语义。
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				TextButton(enabled = canSubmit, onClick = { onDecision("deny", null) }) {
					Text("拒绝")
				}
				TextButton(enabled = false, onClick = { }) {
					Text("始终允许")
				}
			}
		},
	)
}
