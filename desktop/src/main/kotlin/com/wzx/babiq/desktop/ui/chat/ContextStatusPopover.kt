package com.wzx.babiq.desktop.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.CapabilityUiState
import com.wzx.babiq.desktop.state.ContextWindowUiState
import com.wzx.babiq.desktop.state.MemoryUiState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors
import java.text.NumberFormat
import java.util.Locale

/**
 * 输入栏 P3 状态弹层中的一行摘要。
 *
 * @property title 用户可读标题，代表一个上下文治理层，例如上下文窗口、短期压缩或长期记忆。
 * @property detail 该层当前状态的短说明；这里有意保持简短，完整审计仍放在运行详情面板。
 */
data class ContextStatusPopoverEntry(
	val title: String,
	val detail: String,
)

/**
 * 输入栏 P3 状态 chip 对应的弹层类型。
 *
 * 三个 chip 虽然都属于上下文治理区域，但用户点击时的心智目标不同：上下文看窗口预算，长期记忆看读写流水线，
 * 能力看常驻/按需装配。因此这里显式拆开，避免多个 chip 复用同一个上下文弹层。
 */
enum class P3StatusPopoverKind {
	CONTEXT,
	MEMORY,
	CAPABILITY,
}

/**
 * P3 状态弹层的纯数据模型。
 *
 * @property title 弹层标题，必须和被点击的 chip 语义一致。
 * @property description 标题下方的短说明，用来解释该状态层如何参与本轮模型输入。
 * @property metrics 顶部指标卡片，保持 2-3 个最关键数字，避免把运行审计搬进输入栏。
 * @property entries 详细条目列表，展示当前层的开关、计数和最近产物。
 * @property footer 底部边界说明，告诉用户真实执行仍由后端审计链路控制。
 */
data class P3StatusPopoverModel(
	val title: String,
	val description: String,
	val metrics: List<ContextStatusPopoverEntry>,
	val entries: List<ContextStatusPopoverEntry>,
	val footer: String,
)

/**
 * 根据被点击的 P3 chip 生成独立弹层模型。
 *
 * 这是 Compose 之外的纯函数，测试可以直接验证“长期记忆/能力不会再落到上下文窗口标题和条目”。
 */
fun buildP3StatusPopoverModel(
	kind: P3StatusPopoverKind,
	context: ContextWindowUiState,
	memory: MemoryUiState,
	capability: CapabilityUiState,
): P3StatusPopoverModel =
	when (kind) {
		P3StatusPopoverKind.CONTEXT -> {
			val status = context.status
			P3StatusPopoverModel(
				title = "本轮上下文窗口",
				description = "当前用户问题始终最高优先级，历史、记忆和能力目录只作为参考层注入。",
				metrics = listOf(
					ContextStatusPopoverEntry("模型窗口", status?.modelContextWindow?.let(::formatInteger) ?: "未加载"),
					ContextStatusPopoverEntry("当前估算", status?.lastEstimatedTokens?.let(::formatInteger) ?: "未生成"),
					ContextStatusPopoverEntry(
						"自动压缩阈值",
						status?.autoCompactThreshold?.let { threshold ->
							if (status.modelContextWindow > 0) {
								percentageLabel(threshold.toDouble() / status.modelContextWindow)
							} else {
								formatInteger(threshold)
							}
						} ?: "未配置",
					),
				),
				entries = buildContextStatusPopoverEntries(context, memory, capability),
				footer = "旧历史会以 REPLACED_BY_SUMMARY 等原因保留审计记录；Skill 正文只在显式读取或命中后进入上下文。",
			)
		}

		P3StatusPopoverKind.MEMORY -> P3StatusPopoverModel(
			title = "长期记忆状态",
			description = "长期记忆由后台异步抽取和归并；本轮只按预算注入摘要或检索引用。",
			metrics = memoryMetrics(memory),
			entries = memoryPopoverEntries(memory),
			footer = "长期记忆不会直接写入聊天消息；后端会按预算把 memory_summary 或检索引用注入 long_term_memory 参考层。",
		)

		P3StatusPopoverKind.CAPABILITY -> P3StatusPopoverModel(
			title = "能力装配状态",
			description = "常驻能力直接进入模型工具列表，按需能力通过能力搜索命中后再提升。",
			metrics = capabilityMetrics(capability),
			entries = capabilityPopoverEntries(capability),
			footer = "能力 name 保持 ASCII；中文 query 通过 searchText 别名命中，实际执行仍走审批、沙箱和工具审计。",
		)
	}

/**
 * 把后端 P3 状态聚合成弹层条目。
 *
 * 这个纯函数让测试可以验证 UI 语义，不需要启动 Compose；它只读取已经同步到 AppState 的状态，
 * 不主动触发任何 JSON-RPC，避免用户点开弹层时改变本轮上下文事实。
 */
fun buildContextStatusPopoverEntries(
	context: ContextWindowUiState,
	memory: MemoryUiState,
	capability: CapabilityUiState,
): List<ContextStatusPopoverEntry> =
	listOf(
		ContextStatusPopoverEntry("上下文窗口", contextWindowDetail(context)),
		ContextStatusPopoverEntry("短期压缩", compactionDetail(context)),
		ContextStatusPopoverEntry("长期记忆", memoryDetail(memory)),
		ContextStatusPopoverEntry("能力装配", capabilityDetail(capability)),
	)

/**
 * 点击输入栏 P3 chip 后展示的上下文治理弹层。
 *
 * Figma 中的原型强调“本轮输入最高优先级，历史/记忆/能力都是参考层”，这里用紧凑指标和条目表表达同一件事，
 * 避免把完整快照 JSON 塞进底部输入区。
 */
@Composable
fun ContextStatusPopover(
	context: ContextWindowUiState,
	memory: MemoryUiState,
	capability: CapabilityUiState,
	modifier: Modifier = Modifier,
	kind: P3StatusPopoverKind = P3StatusPopoverKind.CONTEXT,
) {
	val model = buildP3StatusPopoverModel(kind, context, memory, capability)
	Card(
		modifier = modifier.widthIn(min = 420.dp, max = 560.dp),
		shape = RoundedCornerShape(12.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(model.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			Text(
				model.description,
				style = MaterialTheme.typography.bodySmall,
				color = BaBiQColors.Muted,
			)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				model.metrics.forEach { metric ->
					MetricTile(metric.title, metric.detail, Modifier.weight(1f))
				}
			}
			HorizontalDivider(color = BaBiQColors.Border)
			model.entries.forEach { entry ->
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
					Text(entry.title, modifier = Modifier.weight(0.32f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
					Text(entry.detail, modifier = Modifier.weight(0.68f), style = MaterialTheme.typography.bodySmall, color = BaBiQColors.Muted)
				}
			}
			Text(
				model.footer,
				style = MaterialTheme.typography.labelSmall,
				color = BaBiQColors.Muted,
			)
		}
	}
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
	Card(
		modifier = modifier,
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
			Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
			Text(label, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
		}
	}
}

private fun contextWindowDetail(state: ContextWindowUiState): String {
	if (state.loading) return "正在读取上下文状态"
	state.error?.let { return "读取失败：$it" }
	val status = state.status ?: return "尚未生成模型输入快照"
	return buildString {
		append(percentageLabel(status.usageRatio))
		append(" · ")
		append(formatInteger(status.lastEstimatedTokens))
		append(" / ")
		append(formatInteger(status.modelContextWindow))
		append(" token")
		status.lastSnapshotId?.let { append(" · ").append(it) }
	}
}

private fun compactionDetail(state: ContextWindowUiState): String {
	val status = state.status ?: return "尚未触发短期压缩"
	if (status.compactionCount <= 0) return "未压缩 · 阈值 ${formatInteger(status.autoCompactThreshold)} token"
	return buildString {
		append(status.compactionCount).append(" 次")
		status.activeSummaryId?.let { append(" · ").append(it) }
		status.lastCompactionStatus?.let { append(" · ").append(it) }
	}
}

private fun memoryDetail(state: MemoryUiState): String {
	if (state.loading) return "正在读取长期记忆状态"
	state.error?.let { return "读取失败：$it" }
	val status = state.status ?: return "尚未加载长期记忆"
	if (!status.enabled) return "已关闭"
	return buildString {
		append(if (status.readEnabled) "注入开" else "不注入")
		append(" · G").append(status.phase2Generation)
		append(" · CLEAN ").append(status.cleanCandidateCount)
		if (status.retrievalEnabled) append(" · 检索增强")
	}
}

private fun memoryMetrics(state: MemoryUiState): List<ContextStatusPopoverEntry> {
	val status = state.status
	return listOf(
		ContextStatusPopoverEntry("开关", memorySwitchLabel(state)),
		ContextStatusPopoverEntry("未归并", status?.cleanCandidateCount?.let(::formatInteger) ?: "-"),
		ContextStatusPopoverEntry("Generation", status?.phase2Generation?.let { "G$it" } ?: "-"),
	)
}

private fun memoryPopoverEntries(state: MemoryUiState): List<ContextStatusPopoverEntry> {
	if (state.loading || state.error != null || state.status == null) {
		return listOf(ContextStatusPopoverEntry("状态", memoryDetail(state)))
	}
	val status = state.status
	return listOf(
		ContextStatusPopoverEntry("读取注入", if (status.readEnabled) "开启 · 会进入 long_term_memory 参考层" else "关闭 · 本轮不注入"),
		ContextStatusPopoverEntry("生成流水线", if (status.generateEnabled) "开启 · Phase 1/2 可继续处理" else "暂停 · 只保留读取"),
		ContextStatusPopoverEntry("候选/归并", "CLEAN ${formatInteger(status.cleanCandidateCount)} · G${status.phase2Generation}"),
		ContextStatusPopoverEntry("检索增强", if (status.retrievalEnabled) "开启 · 可按本轮问题检索引用" else "关闭 · 仅 summary 注入"),
		ContextStatusPopoverEntry("最近摘要", status.lastSummaryArtifactId ?: "尚未生成 memory_summary"),
	)
}

private fun memorySwitchLabel(state: MemoryUiState): String {
	if (state.loading) return "读取中"
	if (state.error != null) return "异常"
	val status = state.status ?: return "未加载"
	if (!status.enabled) return "关闭"
	return "开启"
}

private fun capabilityDetail(state: CapabilityUiState): String {
	if (state.loading) return "正在读取能力目录"
	state.error?.let { return "读取失败：$it" }
	val status = state.status ?: return "尚未加载能力目录"
	return "常驻 ${status.visibleCount} · 按需 ${status.deferredCount} · 禁用 ${status.disabledCount} · 总计 ${status.totalCount}"
}

private fun capabilityMetrics(state: CapabilityUiState): List<ContextStatusPopoverEntry> {
	val status = state.status
	return listOf(
		ContextStatusPopoverEntry("常驻", status?.visibleCount?.let(::formatInteger) ?: "-"),
		ContextStatusPopoverEntry("按需", status?.deferredCount?.let(::formatInteger) ?: "-"),
		ContextStatusPopoverEntry("总计", status?.totalCount?.let(::formatInteger) ?: "-"),
	)
}

private fun capabilityPopoverEntries(state: CapabilityUiState): List<ContextStatusPopoverEntry> {
	if (state.loading || state.error != null || state.status == null) {
		return listOf(ContextStatusPopoverEntry("状态", capabilityDetail(state)))
	}
	val status = state.status
	return listOf(
		ContextStatusPopoverEntry("常驻能力", "${formatInteger(status.visibleCount)} 个 · 默认进入模型工具列表"),
		ContextStatusPopoverEntry("按需能力", "${formatInteger(status.deferredCount)} 个 · 通过 tool_search 命中后提升"),
		ContextStatusPopoverEntry("禁用能力", "${formatInteger(status.disabledCount)} 个 · 不参与本轮能力装配"),
		ContextStatusPopoverEntry("总目录", "${formatInteger(status.totalCount)} 个 · 已启用 ${formatInteger(status.enabledCount)}"),
	)
}

private fun formatInteger(value: Int): String =
	NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun formatInteger(value: Long): String =
	NumberFormat.getIntegerInstance(Locale.US).format(value)

private fun percentageLabel(value: Double): String =
	"${(value * 100).toInt().coerceAtLeast(0)}%"
