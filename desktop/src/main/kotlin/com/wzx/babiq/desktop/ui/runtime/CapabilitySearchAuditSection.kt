package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 运行详情里的能力搜索审计摘要。
 *
 * P3-5a 已经让能力目录和中文 query 搜索接后端 Lucene/BM25；这里展示真实能力状态和最近一次手动搜索结果。
 * 如果后续后端按 turn 返回 tool_search 命中事件，可以在这个分区扩展为逐 turn 审计。
 */
@Composable
fun CapabilitySearchAuditSection(capability: CapabilityUiState) {
	AuditSectionCard("能力装配") {
		val status = capability.status
		Text(
			if (status == null) {
				"尚未加载能力目录"
			} else {
				"总计 ${status.totalCount} / 常驻 ${status.visibleCount} / 按需 ${status.deferredCount} / 禁用 ${status.disabledCount}"
			},
			style = MaterialTheme.typography.bodySmall,
		)
		if (capability.searchResults.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("最近搜索结果", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
				capability.searchResults.take(5).forEach { item ->
					Text(item.displayName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
					Text(
						"${item.capabilityId} · ${item.type} · ${item.exposureMode}",
						style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
						color = BaBiQColors.Muted,
					)
				}
			}
		}
	}
}
