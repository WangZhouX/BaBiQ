package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.MemoryUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 运行详情里的长期记忆审计摘要。
 *
 * 当前后端 run/turn/get 还没有按 turn 返回完整 memory reference 事件，所以这里只展示已经真实存在的
 * memory/status、artifact 和设置页检索结果；不在桌面端伪造某一轮模型实际引用了哪些记忆。
 */
@Composable
fun MemoryReferenceSection(memory: MemoryUiState) {
	AuditSectionCard("长期记忆") {
		val status = memory.status
		Text(
			if (status == null) {
				"尚未加载长期记忆状态"
			} else {
				"状态: ${if (status.enabled) "已启用" else "已关闭"} / G${status.phase2Generation} / CLEAN ${status.cleanCandidateCount}"
			},
			style = MaterialTheme.typography.bodySmall,
		)
		memory.searchStrategy?.let {
			Text("最近检索: $it / ${memory.searchTokenEstimate} token", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		}
		if (memory.searchResults.isNotEmpty()) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("检索引用", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
				memory.searchResults.take(3).forEach { reference ->
					Text("${reference.confidence} · ${reference.artifactId} · ${reference.tokenEstimate} token", style = MaterialTheme.typography.labelSmall)
					Text(reference.text.take(120), style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
				}
			}
		}
		if (memory.artifacts.isNotEmpty()) {
			Text("最近产物", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
			memory.artifacts.take(3).forEach { artifact ->
				Text("${artifact.artifactType} · ${artifact.artifactPath}", style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
			}
		}
	}
}
