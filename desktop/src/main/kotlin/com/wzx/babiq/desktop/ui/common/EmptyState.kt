package com.wzx.babiq.desktop.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wzx.babiq.desktop.ui.theme.BaBiQColors

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.fillMaxWidth(),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Text(
			text = "我们应该在 BaBiQ 中构建什么？",
			style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium),
		)
		Text(
			text = "输入任务后，桌面端会连接本地 Agent Server 并展示执行过程。",
			color = BaBiQColors.Muted,
			style = MaterialTheme.typography.bodyMedium,
		)
	}
}
