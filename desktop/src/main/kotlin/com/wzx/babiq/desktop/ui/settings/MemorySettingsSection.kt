package com.wzx.babiq.desktop.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 长期记忆设置区的纯展示模型。
 *
 * @property enabledLabel 总开关用户可读文案。
 * @property pipelineLabel Phase1/Phase2 流水线关键数字。
 * @property artifactLabel 最近可用于 read path 的 Markdown 产物摘要。
 * @property metrics Figma 记忆设置页顶部指标卡，帮助用户快速判断流水线是否健康。
 * @property jobRows 最近任务表格行。
 * @property securityBoundaryText 安全边界说明，强调 SECRET_RISK 不会进入 Phase2。
 */
data class MemorySettingsSectionModel(
	val enabledLabel: String,
	val pipelineLabel: String,
	val artifactLabel: String,
	val metrics: List<MemoryMetricCard>,
	val jobRows: List<MemoryJobTableRow>,
	val securityBoundaryText: String,
)

/**
 * 记忆指标卡模型。
 */
data class MemoryMetricCard(
	val label: String,
	val value: String,
	val helper: String,
)

/**
 * 记忆任务表格行模型。
 */
data class MemoryJobTableRow(
	val stage: String,
	val status: String,
	val strategy: String,
	val createdAt: String,
)

/** 记忆设置页统一使用用户本机时区展示秒级时间，避免把 ISO 审计文本直接暴露给用户。 */
private val MemoryDisplayTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 把 memory/status、jobs 和 artifacts 组合成设置页摘要。
 *
 * 这个函数只做展示层聚合，真实开关和任务状态仍以后端 SQLite 返回为准。
 */
fun buildMemorySettingsSectionModel(
	status: MemoryStatusResult?,
	jobs: List<MemoryJobInfo>,
	artifacts: List<MemoryArtifactInfo>,
): MemorySettingsSectionModel {
	val enabledLabel = when {
		status == null -> "未加载"
		status.enabled -> "已启用"
		else -> "已关闭"
	}
	val pipelineLabel = if (status == null) {
		"待加载"
	} else {
		"待执行 ${status.pendingJobs} · 运行中 ${status.runningJobs} · CLEAN ${status.cleanCandidateCount} · G${status.phase2Generation}"
	}
	val artifactLabel = artifacts.firstOrNull()?.let { "${it.artifactPath} · v${it.version} · ${it.tokenEstimate} token" }
		?: "暂无产物"
	val metrics = listOf(
		MemoryMetricCard("开关", enabledLabel, "生成 ${onOff(status?.generateEnabled)} · 注入 ${onOff(status?.readEnabled)} · 检索 ${onOff(status?.retrievalEnabled)}"),
		MemoryMetricCard("未归并候选", (status?.cleanCandidateCount ?: 0).toString(), "CLEAN candidates"),
		MemoryMetricCard("SECRET_RISK", (status?.secretRiskCandidateCount ?: 0).toString(), "高风险候选隔离"),
		MemoryMetricCard("最近归并", formatMemoryTimestamp(status?.lastConsolidatedAt), "Phase2 G${status?.phase2Generation ?: 0}"),
	)
	val jobRows = jobs.map { job ->
		MemoryJobTableRow(
			stage = displayMemoryJobStage(job.jobType),
			status = displayMemoryJobStatus(job.status),
			strategy = displayMemoryJobStrategy(job),
			createdAt = formatMemoryTimestamp(job.createdAt),
		)
	}
	return MemorySettingsSectionModel(
		enabledLabel = enabledLabel,
		pipelineLabel = pipelineLabel,
		artifactLabel = artifactLabel,
		metrics = metrics,
		jobRows = jobRows,
		securityBoundaryText = "SECRET_RISK 候选只进入审计，不进入 Phase2 归并；Markdown mirror 与上下文注入均以后端产物表为准。",
	)
}

private fun onOff(value: Boolean?): String =
	when (value) {
		true -> "开"
		false -> "关"
		null -> "?"
	}

/**
 * 把后端长期记忆 job 类型枚举转换为原型中的中文阶段。
 *
 * <p>协议仍保留 PHASE1/PHASE2 便于审计和跨端兼容，桌面端只在展示层本地化。</p>
 */
internal fun displayMemoryJobStage(stage: String): String =
	when (stage.uppercase()) {
		"PHASE1" -> "候选抽取"
		"PHASE2" -> "全局归并"
		else -> stage
	}

/**
 * 把长期记忆 job 状态转换为中文。
 *
 * <p>这里覆盖流水线当前会出现的状态；未知状态保留原值，方便开发阶段定位后端新增枚举。</p>
 */
internal fun displayMemoryJobStatus(status: String): String =
	when (status.uppercase()) {
		"PENDING" -> "待执行"
		"RUNNING" -> "运行中"
		"SUCCEEDED" -> "已完成"
		"FAILED" -> "失败"
		"SKIPPED_POLLUTED" -> "已隔离"
		"NO_OUTPUT" -> "无输出"
		else -> status
	}

/**
 * 生成用户可读的长期记忆任务策略。
 *
 * <p>Phase1 的 jobKey 是审计去重键，不适合直接给用户看；Phase2 的 generation 则表示第几代长期记忆归并。</p>
 */
internal fun displayMemoryJobStrategy(job: MemoryJobInfo): String =
	when {
		job.jobType.equals("PHASE2", ignoreCase = true) && job.generation > 0 -> "第 ${job.generation} 代归并"
		job.jobType.equals("PHASE2", ignoreCase = true) -> "全局候选归并"
		job.jobType.equals("PHASE1", ignoreCase = true) -> "空闲会话抽取"
		else -> job.jobKey
	}

/**
 * 把后端 ISO-8601 时间转换为桌面端秒级时间。
 *
 * <p>后端为了审计保存 UTC/ISO 字符串；UI 展示时按用户本机时区格式化为 yyyy-MM-dd HH:mm:ss。</p>
 */
internal fun formatMemoryTimestamp(value: String?): String {
	if (value.isNullOrBlank()) {
		return "暂无"
	}
	return runCatching {
		Instant.parse(value).atZone(ZoneId.systemDefault()).format(MemoryDisplayTimeFormatter)
	}.recoverCatching {
		OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(MemoryDisplayTimeFormatter)
	}.recoverCatching {
		LocalDateTime.parse(value).format(MemoryDisplayTimeFormatter)
	}.getOrElse {
		value
	}
}

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
	onScanMemory: () -> Unit,
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
		FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			model.metrics.forEach { metric -> MemoryMetric(metric) }
		}
		MetricLine("流水线", model.pipelineLabel)
		MetricLine("产物", model.artifactLabel)
		Text("目录: ${status?.rootDir ?: "尚未加载"}", style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
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
			OutlinedButton(enabled = state.canEditSettings && !memory.loading, onClick = onScanMemory) {
				Text("立即扫描")
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
		MemorySearchPreview(memory.searchStrategy, memory.searchTokenEstimate, memory.searchResults)
		MemoryJobTable(model.jobRows)
		Column(
			modifier = Modifier.fillMaxWidth().background(BaBiQColors.Background, RoundedCornerShape(8.dp)).padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text("安全边界审计", fontWeight = FontWeight.Bold)
			Text(model.securityBoundaryText, color = BaBiQColors.Muted)
		}
	}
}

@Composable
private fun MemoryMetric(metric: MemoryMetricCard) {
	Column(
		modifier = Modifier.background(BaBiQColors.Background, RoundedCornerShape(8.dp)).padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(metric.value, fontWeight = FontWeight.Bold)
		Text(metric.label, color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
		Text(metric.helper, color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
	}
}

@Composable
private fun MemorySearchPreview(
	strategy: String?,
	tokenEstimate: Int,
	results: List<com.wzx.babiq.desktop.protocol.MemoryReferenceInfo>,
) {
	if (results.isEmpty()) {
		return
	}
	Text("检索结果 · ${strategy ?: "未知策略"} · $tokenEstimate token", fontWeight = FontWeight.Medium)
	results.take(4).forEach { reference ->
		Text("${reference.confidence} · ${reference.artifactId}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
		Text(reference.text.take(160), color = BaBiQColors.Muted)
	}
}

@Composable
private fun MemoryJobTable(rows: List<MemoryJobTableRow>) {
	if (rows.isEmpty()) {
		Text("最近任务: 暂无", color = BaBiQColors.Muted)
		return
	}
	Text("最近任务", fontWeight = FontWeight.Medium)
	Row(modifier = Modifier.fillMaxWidth().background(BaBiQColors.Background, RoundedCornerShape(6.dp)).padding(8.dp)) {
		Text("阶段", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
		Text("最近状态", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
		Text("策略", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
		Text("创建时间", modifier = Modifier.weight(1.4f), fontWeight = FontWeight.Bold)
	}
	rows.take(6).forEach { row ->
		Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
			Text(row.stage, modifier = Modifier.weight(1f))
			Text(row.status, modifier = Modifier.weight(1f), color = BaBiQColors.Muted)
			Text(row.strategy, modifier = Modifier.weight(1f), color = BaBiQColors.Muted)
			Text(row.createdAt, modifier = Modifier.weight(1.4f), color = BaBiQColors.Muted, style = MaterialTheme.typography.labelSmall)
		}
		HorizontalDivider(color = BaBiQColors.Border)
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
			Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			content()
		}
	}
}

@Composable
internal fun MetricLine(label: String, value: String) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Text(label, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		Text(value, style = MaterialTheme.typography.bodyMedium)
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
