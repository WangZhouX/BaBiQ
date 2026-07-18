package com.wzx.huitai.desktop.ui.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessThreadItem

/** 把应用动作从接收、预览、审批、运行到终态的进度映射为用户可读卡片。 */
@Composable
fun ApplicationActionProgressCard(
    action: BusinessThreadItem.ApplicationAction,
    modifier: Modifier = Modifier,
) {
    val normalized = action.status.uppercase()
    Surface(
        modifier = modifier.fillMaxWidth().testTag("application-action-${action.executionId}"),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(action.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(actionStatusLabel(normalized), color = actionStatusColor(normalized))
            }
            Text("执行编号：${action.executionId}", style = MaterialTheme.typography.labelSmall)
            action.previewSummary?.let { Text(it) }
            action.errorSummary?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (normalized == "OUTCOME_UNKNOWN") {
                Text(
                    "结果未知，请先按执行编号对账，确认远端结果后再决定是否重试。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun actionStatusLabel(status: String): String = when (status) {
    "REQUESTED", "RECEIVED", "ACCEPTED", "VALIDATING" -> "已接收"
    "PREVIEW", "PREVIEWED" -> "预览中"
    "APPROVAL", "APPROVAL_REQUIRED", "WAITING_APPROVAL", "AWAITING_APPROVAL" -> "等待审批"
    "RUNNING", "EXECUTING" -> "执行中"
    "SUCCESS", "SUCCEEDED", "COMPLETED" -> "已完成"
    "FAILED" -> "失败"
    "REJECTED" -> "已拒绝"
    "CANCELED", "CANCELLED" -> "已取消"
    "EXPIRED" -> "已过期"
    "OUTCOME_UNKNOWN" -> "结果未知"
    else -> "状态未知"
}

@Composable
private fun actionStatusColor(status: String) = when (status) {
    "FAILED", "OUTCOME_UNKNOWN" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}
