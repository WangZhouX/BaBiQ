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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.state.OrchestrationUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

private const val FlowSummaryMaxChars = 120

/**
 * 流程编排区的纯展示模型。
 *
 * 这里把后端 `orchestration` 协议 item 压成右侧面板需要的字段：标题、拓扑、审批冻结状态、
 * 节点行和短摘要。Composable 不直接理解协议细节，后续如果后端新增字段，只改构建函数即可。
 *
 * @property visible 是否展示流程编排区。
 * @property title 主标题，包含流程名称。
 * @property subtitle 拓扑、状态和审批冻结状态。
 * @property nodes 节点展示行。
 * @property summaryPreview 流程摘要短预览，避免长 Markdown 撑开侧栏。
 */
data class OrchestrationSectionModel(
	val visible: Boolean,
	val title: String,
	val subtitle: String,
	val nodes: List<OrchestrationNodeRow>,
	val summaryPreview: String? = null,
)

/**
 * 流程节点展示行。
 *
 * @property icon 状态图标，和计划区保持一致：完成、运行中、等待、失败。
 * @property title 节点展示名。
 * @property meta 安全模式、模型、工具次数等短字段。
 * @property detail 节点任务或短摘要。
 * @property active true 表示节点正在运行，用于后续样式扩展。
 */
data class OrchestrationNodeRow(
	val icon: String,
	val title: String,
	val meta: String,
	val detail: String,
	val active: Boolean,
)

/**
 * 将 reducer 中的流程编排状态转换为右侧运行面板模型。
 */
fun buildOrchestrationSectionModel(state: OrchestrationUiState): OrchestrationSectionModel {
	val item = state.current ?: return OrchestrationSectionModel(false, "", "", emptyList())
	return OrchestrationSectionModel(
		visible = true,
		title = "流程编排 · ${item.title}",
		subtitle = "${topologyLabel(item.topology)} / ${statusLabel(item.status)} / ${approvalLabel(item)}",
		nodes = item.nodes.map(::nodeRow),
		summaryPreview = compactFlowSummary(item.summary),
	)
}

/**
 * 渲染右侧运行面板里的流程编排状态。
 */
@Composable
fun OrchestrationSection(state: OrchestrationUiState) {
	val model = buildOrchestrationSectionModel(state)
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
			model.nodes.forEach { row -> OrchestrationNodeRowView(row) }
			model.summaryPreview?.let { preview -> FlowSummaryPreview(preview) }
		}
	}
}

/**
 * 单个流程节点行。
 */
@Composable
private fun OrchestrationNodeRowView(row: OrchestrationNodeRow) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				if (row.active) BaBiQColors.Accent.copy(alpha = 0.10f) else BaBiQColors.Accent.copy(alpha = 0.06f),
				RoundedCornerShape(6.dp),
			)
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
			Text(row.icon, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
			Text(row.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
		}
		Text(row.meta, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(
			row.detail,
			style = MaterialTheme.typography.bodySmall,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/**
 * 流程整体摘要短预览。
 */
@Composable
private fun FlowSummaryPreview(preview: String) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(BaBiQColors.Accent.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text("流程摘要", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		Text(
			preview,
			style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

private fun nodeRow(node: ThreadItem.OrchestrationNode): OrchestrationNodeRow =
	OrchestrationNodeRow(
		icon = statusIcon(node.status),
		title = node.displayName?.takeIf { it.isNotBlank() } ?: node.name,
		meta = listOfNotNull(
			modeLabel(node.mode),
			node.model?.takeIf { it.isNotBlank() },
			node.toolCallCount?.let { "工具 $it 次" },
			node.tokenEstimate?.let { "token $it" },
		).joinToString(" · "),
		detail = node.summary?.takeIf { it.isNotBlank() } ?: node.task.orEmpty().ifBlank { node.name },
		active = node.status.equals("running", ignoreCase = true),
	)

private fun statusIcon(status: String): String =
	when (status.lowercase()) {
		"completed" -> "●"
		"running" -> "◐"
		"failed" -> "!"
		else -> "○"
	}

private fun statusLabel(status: String): String =
	when (status.lowercase()) {
		"running" -> "运行中"
		"completed" -> "已完成"
		"failed" -> "失败"
		"canceled" -> "已取消"
		else -> status
	}

private fun topologyLabel(topology: String): String =
	when (topology.lowercase()) {
		"sequential" -> "顺序"
		"parallel" -> "并行"
		"routing" -> "路由"
		else -> topology
	}

private fun modeLabel(mode: String): String =
	when (mode.uppercase()) {
		"READ_ONLY_TOOL" -> "只读工具"
		"WORKSPACE_TOOL" -> "工作区工具"
		else -> mode
	}

private fun approvalLabel(item: ThreadItem.Orchestration): String =
	if (item.approved == true && item.frozen == true) "已审批并冻结" else "未冻结"

private fun compactFlowSummary(summary: String?): String? {
	val compact = summary.orEmpty()
		.lineSequence()
		.map { it.trim() }
		.filter { it.isNotBlank() }
		.map { it.trimStart('#').trim().replace("`", "") }
		.filter { it.isNotBlank() }
		.take(2)
		.joinToString(" · ")
		.replace(Regex("\\s+"), " ")
		.trim()
	if (compact.isBlank()) {
		return null
	}
	return if (compact.length <= FlowSummaryMaxChars) {
		compact
	} else {
		compact.take(FlowSummaryMaxChars - 3).trimEnd() + "..."
	}
}
