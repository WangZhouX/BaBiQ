package com.wzx.huitai.desktop.ui.action

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.action.model.ActionOrigin
import com.wzx.huitai.desktop.decision.ActionDecisionDialogState
import com.wzx.huitai.desktop.decision.ConfirmationDecisionDialogState

/**
 * 展示单次动作的无副作用预览，并把确认或取消回调交还给决策协调器。
 *
 * 弹窗本身不执行动作、不保存授权；本地 submitted 只阻止 Compose 重组窗口内的重复点击。
 */
@Composable
fun ActionPreviewDialog(
    state: ConfirmationDecisionDialogState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var submitted by remember(state.decisionId) { mutableStateOf(false) }

    /** 统一关闭路径，确保窗口关闭手势与取消按钮具备相同的一次性语义。 */
    fun cancelOnce() {
        if (submitted) return
        submitted = true
        onCancel()
    }

    AlertDialog(
        modifier = modifier.testTag("action-preview-dialog-${state.executionId}"),
        onDismissRequest = ::cancelOnce,
        title = { Text("确认动作预览") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DECISION_CONTENT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .testTag("action-preview-scroll-${state.executionId}"),
            ) {
                ActionDecisionBody(state)
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("action-preview-confirm-${state.executionId}"),
                enabled = !submitted,
                onClick = {
                    if (!submitted) {
                        submitted = true
                        onConfirm()
                    }
                },
            ) {
                Text("确认本次动作")
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.testTag("action-preview-cancel-${state.executionId}"),
                enabled = !submitted,
                onClick = ::cancelOnce,
            ) {
                Text("取消")
            }
        },
    )
}

/** 预览与审批复用的只读安全内容区，只渲染协调器已经脱敏的状态。 */
@Composable
internal fun ActionDecisionBody(state: ActionDecisionDialogState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.actionTitle, fontWeight = FontWeight.SemiBold)
        Text("来源：${state.origin.toDisplayLabel()}", style = MaterialTheme.typography.labelMedium)
        Text(state.summary)
        state.differences.forEachIndexed { index, difference ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("action-decision-difference-${state.executionId}-$index")
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(difference.path, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("变更前：${difference.before}", modifier = Modifier.weight(1f))
                    Text("变更后：${difference.after}", modifier = Modifier.weight(1f))
                }
            }
        }
        state.warnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 将协议来源映射为通用中文标签，不暴露其他命令载荷。 */
private fun ActionOrigin.toDisplayLabel(): String = when (this) {
    ActionOrigin.USER -> "用户操作"
    ActionOrigin.AGENT -> "Agent 建议"
}

private val DECISION_CONTENT_MAX_HEIGHT = 360.dp
