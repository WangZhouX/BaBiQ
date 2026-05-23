package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.state.AppState
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 右侧运行详情面板。
 *
 * 它展示和聊天主区同一份状态：当前 turn 状态、最近成本摘要、工具/未知协议事件等。
 */
@Composable
fun RuntimeDetailsPanel(
	state: AppState,
	modifier: Modifier = Modifier,
	onClose: () -> Unit,
) {
	Column(
		modifier = modifier
			.fillMaxHeight()
			.background(BaBiQColors.Panel)
			.padding(16.dp)
			.verticalScroll(rememberScrollState()),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
			Text("运行详情", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
			TextButton(onClick = onClose) { Text("收起") }
		}
		// 成本摘要在这里作为详情复用；聊天流里的 TurnSummaryBar 仍然是主展示位置。
		state.latestSummary?.let { TurnSummaryBar(it) }
		DetailCard("当前状态", "${state.turnState} / ${state.connectionState}")
		state.runtimeEvents.forEach { event ->
			DetailCard(event.title, event.detail + event.raw?.let { "\n$it" }.orEmpty())
		}
		if (state.runtimeEvents.isEmpty() && state.latestSummary == null) {
			Text("暂无运行详情。完成一轮任务后，这里会显示工具轨迹和成本明细。", color = BaBiQColors.Muted)
		}
	}
}

/**
 * 详情面板中的单个信息块。
 */
@Composable
private fun DetailCard(title: String, detail: String) {
	Card(
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Background),
	) {
		Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(title, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
			Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
		}
	}
}
