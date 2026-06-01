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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.SubAgentUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

private const val SummaryPreviewMaxChars = 120

/**
 * 子 Agent 运行区的纯展示模型。
 *
 * 后端用 `agentDelegation` item 表达一次委派生命周期；桌面端先把它压成这个模型，再交给
 * Composable 渲染，避免 UI 组件直接理解协议字段和状态翻译规则。
 *
 * @property visible 是否存在需要展示的委派。
 * @property title 面板标题，通常是子 Agent 名称。
 * @property subtitle 父子 Agent 关系和当前状态。
 * @property rows 结构化明细行，只放稳定短字段，避免长文本撑开右侧面板。
 * @property summaryPreview 子 Agent 最终摘要的短预览；完整 Markdown 仍留在聊天流或运行记录里。
 */
data class SubAgentSectionModel(
	val visible: Boolean,
	val title: String,
	val subtitle: String,
	val rows: List<SubAgentSectionRow>,
	val summaryPreview: String? = null,
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
	if (!state.visible) {
		return SubAgentSectionModel(
			visible = false,
			title = "",
			subtitle = "",
			rows = emptyList(),
		)
	}
	val rows = listOfNotNull(
		SubAgentSectionRow("模式", item.mode),
		item.toolCallCount?.let { SubAgentSectionRow("只读工具", "$it 次") },
		item.tokenEstimate?.let { SubAgentSectionRow("token 估算", it.toString()) },
	)
	return SubAgentSectionModel(
		visible = true,
		title = "子 Agent · ${item.childAgent}",
		subtitle = "${item.parentAgent} -> ${item.childAgent} / ${statusLabel(item.status)}",
		rows = rows,
		summaryPreview = compactSummaryPreview(item.summary),
	)
}

/**
 * 渲染右侧运行面板里的子 Agent 委派状态。
 */
@Composable
fun SubAgentSection(
	state: SubAgentUiState,
	onDismiss: () -> Unit = {},
) {
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
			Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
				Text(model.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
				TextButton(onClick = onDismiss) { Text("移除") }
			}
			Text(model.subtitle, style = MaterialTheme.typography.labelMedium, color = BaBiQColors.Muted)
			model.rows.forEach { row ->
				SubAgentRow(row)
			}
			model.summaryPreview?.let { preview ->
				SubAgentSummaryPreview(preview)
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
 * 子 Agent 摘要短预览。
 *
 * 右侧运行面板只承担“当前执行层级”的轻量说明，完整 Markdown 摘要如果直接放进这里会压缩主聊天区。
 * 因此这里限制最多三行，并交给模型构建阶段提前去掉代码围栏、表格分隔线等噪声。
 */
@Composable
private fun SubAgentSummaryPreview(preview: String) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text("结果预览", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		Text(
			preview,
			style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * 把子 Agent 返回的长 Markdown 摘要压成侧栏预览。
 *
 * explorer 常会返回目录树、代码围栏和表格；这些内容适合放在主对话或详情，不适合常驻侧栏。
 * 这里保留前两条有语义的短行，并做字符上限保护，让右侧面板稳定保持“状态卡片”的信息密度。
 */
private fun compactSummaryPreview(summary: String?): String? {
	val compact = summary.orEmpty()
		.lineSequence()
		.map { it.trim() }
		.filter { it.isNotBlank() }
		.filterNot { it == "```" || it == "---" || it.startsWith("|---") }
		.map { it.trimStart('#').trim().replace("`", "") }
		.filter { it.isNotBlank() }
		.take(2)
		.joinToString(" · ")
		.replace(Regex("\\s+"), " ")
		.trim()

	if (compact.isBlank()) {
		return null
	}
	return if (compact.length <= SummaryPreviewMaxChars) {
		compact
	} else {
		compact.take(SummaryPreviewMaxChars - 3).trimEnd() + "..."
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
		"canceled" -> "已取消"
		else -> status
	}
