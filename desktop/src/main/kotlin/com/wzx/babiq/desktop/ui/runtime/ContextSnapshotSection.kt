package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ContextSnapshotInfo
import com.wzx.babiq.desktop.protocol.ContextSnapshotItemInfo
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 运行详情里的上下文快照展示模型。
 *
 * @property snapshotId 后端持久化的快照 id，用于用户排查某一轮模型输入。
 * @property usageLabel token 使用率的短标签。
 * @property estimatedTokenLabel 本轮上下文估算 token。
 * @property actualPromptTokenLabel 模型返回的真实 prompt token；供应商未返回时显示兜底文本。
 * @property included 实际纳入模型输入的上下文片段。
 * @property excluded 被预算或摘要替换排除的上下文片段。
 */
data class ContextSnapshotSectionModel(
	val snapshotId: String,
	val usageLabel: String,
	val estimatedTokenLabel: String,
	val actualPromptTokenLabel: String,
	val included: List<ContextSnapshotRowModel>,
	val excluded: List<ContextSnapshotRowModel>,
)

/**
 * 快照中单个上下文片段的 UI 行模型。
 */
data class ContextSnapshotRowModel(
	val sourceId: String,
	val sourceType: String,
	val priority: String,
	val reasonLabel: String,
	val tokenLabel: String,
)

/**
 * 把后端快照 DTO 转成运行详情可读分区。
 *
 * 快照是审计事实源，UI 只做分组和中文化，不改变 included/excluded 的语义。
 */
fun buildContextSnapshotSectionModel(snapshot: ContextSnapshotInfo): ContextSnapshotSectionModel {
	val rows = snapshot.items.map(::toRowModel)
	return ContextSnapshotSectionModel(
		snapshotId = snapshot.snapshotId,
		usageLabel = percent(snapshot.usageRatio),
		estimatedTokenLabel = "${snapshot.estimatedTokens} token",
		actualPromptTokenLabel = snapshot.actualPromptTokens?.let { "$it token" } ?: "未返回",
		included = rows.filterIndexed { index, _ -> snapshot.items[index].included },
		excluded = rows.filterIndexed { index, _ -> !snapshot.items[index].included },
	)
}

/**
 * 运行详情面板中的 P3 上下文快照分区。
 */
@Composable
fun ContextSnapshotSection(snapshot: ContextSnapshotInfo) {
	val model = buildContextSnapshotSectionModel(snapshot)
	AuditSectionCard("上下文快照") {
		Text("snapshot: ${model.snapshotId}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
			SnapshotMetric("使用率", model.usageLabel, Modifier.weight(1f))
			SnapshotMetric("估算", model.estimatedTokenLabel, Modifier.weight(1f))
			SnapshotMetric("真实 prompt", model.actualPromptTokenLabel, Modifier.weight(1f))
		}
		SnapshotRows("已纳入", model.included)
		SnapshotRows("已排除", model.excluded)
	}
}

@Composable
private fun SnapshotRows(title: String, rows: List<ContextSnapshotRowModel>) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(title, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
		if (rows.isEmpty()) {
			Text("暂无记录", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		}
		rows.take(8).forEach { row ->
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text("${row.sourceType} · ${row.priority} · ${row.tokenLabel}", style = MaterialTheme.typography.bodySmall)
				Text("${row.sourceId} · ${row.reasonLabel}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			}
			HorizontalDivider(color = BaBiQColors.Border)
		}
	}
}

@Composable
private fun SnapshotMetric(label: String, value: String, modifier: Modifier = Modifier) {
	Card(
		modifier = modifier,
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(value, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			Text(label, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		}
	}
}

private fun toRowModel(item: ContextSnapshotItemInfo): ContextSnapshotRowModel =
	ContextSnapshotRowModel(
		sourceId = item.sourceId,
		sourceType = item.sourceType,
		priority = item.priority,
		reasonLabel = reasonLabel(item.reason),
		tokenLabel = "${item.tokenEstimate} token",
	)

private fun reasonLabel(reason: String): String =
	when (reason.uppercase()) {
		"CURRENT_TURN" -> "本轮用户输入最高优先级"
		"MEMORY_REFERENCE" -> "长期记忆引用"
		"REPLACED_BY_SUMMARY" -> "旧历史已由短期摘要替换"
		"TRIMMED_BY_BUDGET" -> "超过预算被裁剪"
		"CAPABILITY_CATALOG" -> "能力目录摘要"
		else -> reason
	}

private fun percent(value: Double): String =
	"${(value * 100).toInt().coerceAtLeast(0)}%"

/**
 * 运行详情审计分区共用容器，保持 P3 右侧面板的信息密度一致。
 */
@Composable
internal fun AuditSectionCard(
	title: String,
	content: @Composable () -> Unit,
) {
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}
