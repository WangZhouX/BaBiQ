package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import java.text.NumberFormat
import java.util.Locale

/**
 * 成本摘要条里的单个指标。
 */
data class SummaryMetric(
	val label: String,
	val helper: String,
)

/**
 * 格式化美元成本；null 表示后端没有给出估算。
 */
fun formatCostUsd(value: Double?): String =
	if (value == null) {
		"--"
	} else {
		"$" + "%.4f".format(Locale.US, value)
	}

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
		SummaryMetric(formatCostUsd(estimatedCostUsd), "估算成本"),
		SummaryMetric(formatDuration(durationMs), "耗时"),
		SummaryMetric("$toolCalls 工具", "工具调用"),
	)
}
