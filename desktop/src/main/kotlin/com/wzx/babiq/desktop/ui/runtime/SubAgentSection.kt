package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.SubAgentUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 子 Agent 运行区的纯展示模型。
 *
 * 后端用 `agentDelegation` item 表达一次委派生命周期；桌面端先把它压成这个模型，再交给
 * Composable 渲染，避免 UI 组件直接理解协议字段和状态翻译规则。
 *
 * @property visible 是否存在需要展示的委派。
 * @property title 面板标题，通常是子 Agent 名称。
 * @property subtitle 父子 Agent 关系和当前状态。
 * @property rows 结构化明细行，包含模式、工具次数、token 估算和摘要。
 */
data class SubAgentSectionModel(
	val visible: Boolean,
	val title: String,
	val subtitle: String,
	val rows: List<SubAgentSectionRow>,
)

/**
 * 子 Agent 明细行。
 *
 * @property label 左侧字段名，使用中文面向用户。
 * @property value 右侧字段值，来自后端协议或本地状态翻译。
 */
data class SubAgentSectionRow(
	val label: String,
	val value: String,
)

/**
 * 将 reducer 中的子 Agent 状态转换为右侧运行面板模型。
 */
fun buildSubAgentSectionModel(state: SubAgentUiState): SubAgentSectionModel {
	val item = state.current ?: return SubAgentSectionModel(
		visible = false,
		title = "",
		subtitle = "",
		rows = emptyList(),
	)
	val rows = listOfNotNull(
		SubAgentSectionRow("模式", item.mode),
		item.toolCallCount?.let { SubAgentSectionRow("只读工具", "$it 次") },
		item.tokenEstimate?.let { SubAgentSectionRow("token 估算", it.toString()) },
		item.summary?.takeIf { it.isNotBlank() }?.let { SubAgentSectionRow("摘要", it) },
	)
	return SubAgentSectionModel(
		visible = true,
		title = "子 Agent · ${item.childAgent}",
		subtitle = "${item.parentAgent} -> ${item.childAgent} / ${statusLabel(item.status)}",
		rows = rows,
	)
}

/**
 * 渲染右侧运行面板里的子 Agent 委派状态。
 */
@Composable
fun SubAgentSection(state: SubAgentUiState) {
	val model = buildSubAgentSectionModel(state)
	if (!model.visible) {
		return
	}
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
			Text(model.subtitle, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
			model.rows.forEach { row ->
				SubAgentRow(row)
			}
		}
	}
}

/**
 * 子 Agent 明细单行渲染。
 */
@Composable
private fun SubAgentRow(row: SubAgentSectionRow) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(row.label, style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		Text(row.value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
	}
}

/**
 * 将后端状态码翻译成桌面端中文短语；未知状态保留原文，便于排查新协议。
 */
private fun statusLabel(status: String): String =
	when (status.lowercase()) {
		"running" -> "运行中"
		"completed" -> "已完成"
		"failed" -> "失败"
		else -> status
	}
