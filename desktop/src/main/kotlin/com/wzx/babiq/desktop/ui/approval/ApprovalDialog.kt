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

@Composable
fun ApprovalDialog(
	approval: PendingApproval?,
	canSubmit: Boolean,
	onDismiss: () -> Unit,
	onDecision: (String, String?) -> Unit,
) {
	if (approval == null) {
		return
	}
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
			}
		},
		confirmButton = {
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
