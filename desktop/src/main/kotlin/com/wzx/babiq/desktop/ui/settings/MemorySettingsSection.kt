package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.MemoryArtifactInfo
import com.wzx.babiq.desktop.protocol.MemoryJobInfo
import com.wzx.babiq.desktop.protocol.MemoryStatusResult
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 长期记忆设置分区的纯展示模型。
 *
 * @property enabledLabel 总开关用户可读文案。
 * @property pipelineLabel Phase1/Phase2 流水线关键数字。
 * @property artifactLabel 最近可用于 read path 的 Markdown 产物摘要。
 */
data class MemorySettingsSectionModel(
	val enabledLabel: String,
	val pipelineLabel: String,
	val artifactLabel: String,
)

/**
 * 把 memory/status、jobs 和 artifacts 组合成设置页摘要。
 *
 * 这个函数只做展示层聚合，真实开关和任务状态仍以后端 SQLite 返回为准。
 */
fun buildMemorySettingsSectionModel(
	status: MemoryStatusResult?,
	jobs: List<MemoryJobInfo>,
	artifacts: List<MemoryArtifactInfo>,
): MemorySettingsSectionModel =
	MemorySettingsSectionModel(
		enabledLabel = when {
			status == null -> "未加载"
			status.enabled -> "已启用"
			else -> "已关闭"
		},
		pipelineLabel = if (status == null) {
			"待加载"
		} else {
			"待执行 ${status.pendingJobs} · 运行中 ${status.runningJobs} · CLEAN ${status.cleanCandidateCount} · G${status.phase2Generation}"
		},
		artifactLabel = artifacts.firstOrNull()?.let { "${it.artifactPath} · v${it.version} · ${it.tokenEstimate} token" }
			?: "暂无产物",
	)

/**
 * P3 长期记忆设置区。
 *
 * 这里的按钮全部走后端 JSON-RPC：`memory/settings/set`、`memory/consolidate` 和 `memory/search`。
 * UI 不直接修改长期记忆事实源，只展示请求完成后的状态快照。
 */
@Composable
fun MemorySettingsSection(
	state: AppState,
	onSaveMemorySettings: (Boolean?, Boolean?, Boolean?, Boolean?) -> Unit,
	onConsolidateMemory: () -> Unit,
	onSearchMemory: (String) -> Unit,
) {
	val memory = state.memoryState
	val status = memory.status
	val model = buildMemorySettingsSectionModel(status, memory.jobs, memory.artifacts)
	var query by remember(memory.searchQuery) { mutableStateOf(memory.searchQuery) }

	SettingsSectionCard("长期记忆") {
		memory.notice?.let { Text(it, color = BaBiQColors.Success) }
		memory.error?.let { Text("长期记忆错误: $it", color = BaBiQColors.Danger) }
		MetricLine("状态", model.enabledLabel)
		MetricLine("流水线", model.pipelineLabel)
		MetricLine("产物", model.artifactLabel)
		Text("目录: ${status?.rootDir ?: "尚未加载"}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			BooleanSettingButton("总开关", status?.enabled, state.canEditSettings) {
				onSaveMemorySettings(!(status?.enabled ?: true), null, null, null)
			}
			BooleanSettingButton("后台生成", status?.generateEnabled, state.canEditSettings) {
				onSaveMemorySettings(null, !(status?.generateEnabled ?: true), null, null)
			}
			BooleanSettingButton("上下文注入", status?.readEnabled, state.canEditSettings) {
				onSaveMemorySettings(null, null, !(status?.readEnabled ?: true), null)
			}
			BooleanSettingButton("检索增强", status?.retrievalEnabled, state.canEditSettings) {
				onSaveMemorySettings(null, null, null, !(status?.retrievalEnabled ?: true))
			}
			OutlinedButton(enabled = state.canEditSettings && !memory.loading, onClick = onConsolidateMemory) {
				Text("手动归并")
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				modifier = Modifier.weight(1f),
				singleLine = true,
				label = { Text("测试记忆检索") },
			)
			OutlinedButton(enabled = query.isNotBlank() && !memory.loading, onClick = { onSearchMemory(query) }) {
				Text("搜索")
			}
		}
		if (memory.searchResults.isNotEmpty()) {
			Text("检索结果 · ${memory.searchStrategy ?: "未知策略"} · ${memory.searchTokenEstimate} token", fontWeight = FontWeight.Medium)
			memory.searchResults.take(4).forEach { reference ->
				Text("${reference.confidence} · ${reference.artifactId}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
				Text(reference.text.take(160), color = BaBiQColors.Muted)
			}
		}
		if (memory.jobs.isNotEmpty()) {
			Text("最近任务", fontWeight = FontWeight.Medium)
			memory.jobs.take(4).forEach { job -> Text("${job.jobType} · ${job.status} · ${job.jobKey}", color = BaBiQColors.Muted) }
		}
	}
}

@Composable
internal fun SettingsSectionCard(
	title: String,
	content: @Composable ColumnScope.() -> Unit,
) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
			Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}

@Composable
internal fun MetricLine(label: String, value: String) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(value, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
	}
}

@Composable
internal fun BooleanSettingButton(
	label: String,
	current: Boolean?,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val selected = current == true
	val text = "$label:${if (selected) "开" else "关"}"
	if (selected) {
		Button(enabled = enabled, onClick = onClick) { Text(text) }
	} else {
		OutlinedButton(enabled = enabled, onClick = onClick) { Text(text) }
	}
}
