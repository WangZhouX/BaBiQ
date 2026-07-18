package com.wzx.huitai.desktop.ui.action

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.desktop.decision.HighRiskApprovalDialogState

/**
 * 展示每个 execution 独立的高风险审批，不提供任何会话级、记忆型或永久授权选项。
 *
 * 只有用户勾选明确同意后，本次批准按钮才可用；拒绝始终不要求勾选。
 */
@Composable
fun HighRiskApprovalDialog(
    state: HighRiskApprovalDialogState,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var consentChecked by remember(state.decisionId) { mutableStateOf(false) }
    var submitted by remember(state.decisionId) { mutableStateOf(false) }

    /** 统一拒绝路径，避免关闭手势和拒绝按钮重复提交。 */
    fun denyOnce() {
        if (submitted) return
        submitted = true
        onDeny()
    }

    AlertDialog(
        modifier = modifier.testTag("high-risk-approval-dialog-${state.executionId}"),
        onDismissRequest = ::denyOnce,
        title = { Text("高风险动作审批") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = HIGH_RISK_CONTENT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .testTag("high-risk-scroll-${state.executionId}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionDecisionBody(state)
                    state.riskReasons.forEach { reason ->
                        Text(
                            "风险原因：$reason",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(state.identitySummary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        state.remoteSideEffectWarning,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("high-risk-consent-${state.executionId}")
                        .toggleable(
                            value = consentChecked,
                            enabled = !submitted,
                            role = Role.Checkbox,
                            onValueChange = { consentChecked = it },
                        )
                        .semantics { contentDescription = "仅批准本次高风险动作" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = consentChecked,
                        enabled = !submitted,
                        onCheckedChange = null,
                    )
                    Text("我已核对差异，并仅批准本次执行")
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("high-risk-approve-${state.executionId}"),
                enabled = consentChecked && !submitted,
                onClick = {
                    if (consentChecked && !submitted) {
                        submitted = true
                        onApprove()
                    }
                },
            ) {
                Text("批准本次执行")
            }
        },
        dismissButton = {
            OutlinedButton(
                modifier = Modifier.testTag("high-risk-deny-${state.executionId}"),
                enabled = !submitted,
                onClick = ::denyOnce,
            ) {
                Text("拒绝")
            }
        },
    )
}

private val HIGH_RISK_CONTENT_MAX_HEIGHT = 360.dp
