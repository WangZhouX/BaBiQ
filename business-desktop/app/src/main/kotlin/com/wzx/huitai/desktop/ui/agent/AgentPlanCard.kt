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
import androidx.compose.ui.unit.dp
import com.wzx.huitai.agent.conversation.BusinessThreadItem

/** 渲染 reducer 已替换为最新版本的当前计划，不维护第二份计划状态。 */
@Composable
fun AgentPlanCard(
    plan: BusinessThreadItem.Plan,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("agent-plan"),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前计划", style = MaterialTheme.typography.titleMedium)
            plan.goal?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            plan.steps.sortedBy { it.order }.forEach { step ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${step.order}. ${step.description}", modifier = Modifier.weight(1f))
                    Text(
                        planStatusLabel(step.status),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun planStatusLabel(status: String): String = when (status.lowercase()) {
    "pending" -> "等待确认"
    "in_progress", "running" -> "进行中"
    "completed", "done" -> "已完成"
    "failed" -> "失败"
    else -> "状态未知"
}
