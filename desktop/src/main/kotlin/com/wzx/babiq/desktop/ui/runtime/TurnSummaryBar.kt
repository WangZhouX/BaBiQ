package com.wzx.babiq.desktop.ui.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wzx.babiq.desktop.protocol.ThreadItem
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

@Composable
fun TurnSummaryBar(summary: ThreadItem.TurnSummary) {
	Card(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(8.dp),
		border = BorderStroke(1.dp, BaBiQColors.Border),
		colors = CardDefaults.cardColors(containerColor = BaBiQColors.Panel),
	) {
		Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("本轮成本反馈", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				summary.toSummaryMetrics().forEach { metric ->
					Column(modifier = Modifier.weight(1f)) {
						Text(metric.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
						Text(metric.helper, style = MaterialTheme.typography.labelSmall, color = BaBiQColors.Muted)
					}
				}
			}
		}
	}
}
