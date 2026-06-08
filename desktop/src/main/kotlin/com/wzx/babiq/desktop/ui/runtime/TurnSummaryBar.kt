package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

/**
 * 本轮运行反馈条。
 *
 * 只渲染后端真实发来的 turnSummary，不在 idle/running 状态下做前端估算或本地补算。
 */
@Composable
fun TurnSummaryBar(summary: ThreadItem.TurnSummary) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.Center,
	) {
		Card(
			modifier = Modifier.widthIn(max = 520.dp),
			shape = RoundedCornerShape(999.dp),
			border = BorderStroke(1.dp, BaBiQColors.Border),
			colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA)),
		) {
			Text(
				text = summary.toCompactSummaryText(),
				modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
				style = MaterialTheme.typography.labelMedium,
				color = BaBiQColors.Muted,
			)
		}
	}
}
