package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import java.text.NumberFormat
import java.util.Locale

/**
 * 本轮运行反馈条里的单个指标。
 *
 * @property label 主展示文本，通常是 token 数、耗时或工具调用数。
 * @property helper 辅助说明，帮助刚接触模型统计的读者理解这个指标来自哪里。
 */
data class SummaryMetric(
	val label: String,
	val helper: String,
)

/**
 * 把毫秒耗时转换为中文秒数显示。
 */
fun formatDuration(durationMs: Long): String =
	"%.1f 秒".format(Locale.CHINA, durationMs / 1000.0)

/**
 * 把后端 turnSummary item 转换成 UI 指标列表。
 */
fun ThreadItem.TurnSummary.toSummaryMetrics(): List<SummaryMetric> {
	// token 数用英文数字分组，和多数模型控制台显示习惯一致。
	val integerFormat = NumberFormat.getIntegerInstance(Locale.US)
	return listOf(
		SummaryMetric("输入 ${integerFormat.format(promptTokens)}", "prompt tokens"),
		SummaryMetric("输出 ${integerFormat.format(completionTokens)}", "completion tokens"),
		// 前端只展示真实 token 用量，不再根据模型名或价格表展示估算费用。
		SummaryMetric("总计 ${integerFormat.format(totalTokens)}", "total tokens"),
		SummaryMetric(formatDuration(durationMs), "耗时"),
		SummaryMetric("$toolCalls 工具", "工具调用"),
	)
}
