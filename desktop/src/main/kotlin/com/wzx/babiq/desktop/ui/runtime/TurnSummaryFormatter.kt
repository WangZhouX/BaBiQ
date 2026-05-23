package com.wzx.babiq.desktop.ui.runtime

import com.wzx.babiq.desktop.protocol.ThreadItem
import java.text.NumberFormat
import java.util.Locale

data class SummaryMetric(
	val label: String,
	val helper: String,
)

fun formatCostUsd(value: Double?): String =
	if (value == null) {
		"--"
	} else {
		"$" + "%.4f".format(Locale.US, value)
	}

fun formatDuration(durationMs: Long): String =
	"%.1f 秒".format(Locale.CHINA, durationMs / 1000.0)

fun ThreadItem.TurnSummary.toSummaryMetrics(): List<SummaryMetric> {
	val integerFormat = NumberFormat.getIntegerInstance(Locale.US)
	return listOf(
		SummaryMetric("输入 ${integerFormat.format(promptTokens)}", "prompt tokens"),
		SummaryMetric("输出 ${integerFormat.format(completionTokens)}", "completion tokens"),
		SummaryMetric(formatCostUsd(estimatedCostUsd), "估算成本"),
		SummaryMetric(formatDuration(durationMs), "耗时"),
		SummaryMetric("$toolCalls 工具", "工具调用"),
	)
}
